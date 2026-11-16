package app.jordy.igfold;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.ActionMode;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

/**
 * Runtime half of the Instagram Fold patches (Z Fold 8 baseline:
 * cover 475dp wide @ ~420dpi, inner 559dp wide @ ~530dpi).
 *
 * Works through resource entry names, not obfuscated classes, so it
 * should survive most Instagram updates. Bytecode hooks call:
 *   install(Application)        from InstagramAppShell.onCreate
 *   insetRatio(float)           around every getLargeScreenInsetContentRatio return
 */
@SuppressWarnings("unused")
public final class Fold {

    // ---- toggles ----
    public static boolean CAP_60HZ = true;
    public static boolean FLOATING_PILL = true;
    public static boolean COVER_FULL_BLEED_REELS = true;
    public static boolean FORCE_TWO_PANE = true;

    // ---- thresholds (dp) ----
    private static final int COVER_MAX_SW = 520;    // cover = 475
    private static final int TWO_PANE_MIN_SW = 540; // inner = 559
    private static final int TABLET_SW = 600;       // Instagram's tablet line

    // ---- pill look (dp) ----
    private static final int PILL_RADIUS = 32;
    private static final int PILL_SIDE_MARGIN = 16;
    private static final int PILL_BOTTOM_MARGIN = 12;
    private static final int PILL_MAX_WIDTH = 420;
    private static final int PILL_ELEVATION = 8;
    private static final int PILL_COLOR = 0xE61C1C1E; // 90% near-black
    private static final int HIDE_MS = 220;
    private static final int SHOW_MS = 420;
    private static final int SCROLL_THRESHOLD = 24;

    // ---- Reels rail on the cover (dp/sp) ----
    private static final int UFI_ICON = 28;

    // setTag(int) needs an app-range key (high byte >= 0x02); picked to avoid IG's own ids.
    private static final int TAG = 0x7ffe0f01;

    private static boolean installed;
    private static volatile boolean coverNow;
    private static WeakReference<View> pillRef = new WeakReference<>(null);
    private static boolean pillHidden;

    private Fold() {}

    // ============================================================ entry points

    public static void install(Application app) {
        if (installed) return;
        installed = true;
        applyWidthOverride(app.getResources());
        app.registerActivityLifecycleCallbacks(new Callbacks());
    }

    /** Wrapped around getLargeScreenInsetContentRatio(): kills the inset card on the cover. */
    public static Float insetRatio(Float original) {
        return (COVER_FULL_BLEED_REELS && coverNow) ? null : original;
    }

    // ============================================================ lifecycle

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityPreCreated(Activity a, Bundle b) {
            applyWidthOverride(a.getResources());
        }
        @Override public void onActivityCreated(Activity a, Bundle b) {
            capRefreshRate(a);
            Window w = a.getWindow();
            if (FLOATING_PILL && !(w.getCallback() instanceof TouchTracker)) {
                w.setCallback(new TouchTracker(w.getCallback(), a));
            }
        }
        @Override public void onActivityResumed(Activity a) {
            capRefreshRate(a);
            final View decor = a.getWindow().getDecorView();
            if (decor.getTag(TAG) != null) return;
            decor.setTag(TAG, Boolean.TRUE);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() { onLayout(decor); }
                    });
        }
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }

    // ============================================================ two-pane gate

    /** Report 600dp to Instagram on the 559dp inner screen so its tablet layouts turn on. */
    @SuppressWarnings("deprecation")
    private static void applyWidthOverride(Resources res) {
        Configuration c = res.getConfiguration();
        int sw = c.smallestScreenWidthDp;
        coverNow = sw > 0 && sw <= COVER_MAX_SW;
        if (!FORCE_TWO_PANE) return;
        if (sw >= TWO_PANE_MIN_SW && sw < TABLET_SW) {
            Configuration o = new Configuration(c);
            o.smallestScreenWidthDp = TABLET_SW;
            if (o.screenWidthDp < TABLET_SW && o.screenWidthDp >= TWO_PANE_MIN_SW) {
                o.screenWidthDp = TABLET_SW;
            }
            DisplayMetrics dm = res.getDisplayMetrics();
            res.updateConfiguration(o, dm);
        }
    }

    // ============================================================ battery

    /** 60Hz cap: the drain is screen-on, and scrolling at 120Hz is the biggest chunk of it. */
    private static void capRefreshRate(Activity a) {
        if (!CAP_60HZ) return;
        Window w = a.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        Display d = Build.VERSION.SDK_INT >= 30 ? a.getDisplay() : a.getWindowManager().getDefaultDisplay();
        if (d != null) {
            Display.Mode cur = d.getMode();
            Display.Mode best = null;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                        || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                if (best == null || Math.abs(m.getRefreshRate() - 60f) < Math.abs(best.getRefreshRate() - 60f)) best = m;
            }
            if (best != null) lp.preferredDisplayModeId = best.getModeId();
        }
        lp.preferredRefreshRate = 60f;
        w.setAttributes(lp);
    }

    // ============================================================ layout pass

    private static void onLayout(View root) {
        Resources r = root.getResources();
        coverNow = r.getConfiguration().smallestScreenWidthDp <= COVER_MAX_SW;
        if (FLOATING_PILL) stylePill(root);
        if (COVER_FULL_BLEED_REELS && coverNow) fillReels(root);
    }

    // ---- floating pill ----

    private static void stylePill(View root) {
        View bar = byName(root, "fixed_tabbar_view");
        if (bar == null) bar = byName(root, "fixed_tab_bar");
        if (bar == null || bar.getTag(TAG) != null) return;
        bar.setTag(TAG, Boolean.TRUE);

        float dp = root.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PILL_COLOR);
        bg.setCornerRadius(PILL_RADIUS * dp);
        bar.setBackground(bg);
        bar.setElevation(PILL_ELEVATION * dp);
        bar.setClipToOutline(true);

        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            int screenW = root.getWidth();
            int side = Math.max((int) (PILL_SIDE_MARGIN * dp), (screenW - (int) (PILL_MAX_WIDTH * dp)) / 2);
            m.leftMargin = side;
            m.rightMargin = side;
            m.bottomMargin = (int) (PILL_BOTTOM_MARGIN * dp);
            bar.setLayoutParams(m);
        }
        // Let content draw under the pill instead of leaving a black strip.
        if (bar.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) bar.getParent();
            p.setClipChildren(false);
            p.setBackground(null);
        }
        pillRef = new WeakReference<>(bar);
        pillHidden = false;
    }

    static void setPillHidden(boolean hide) {
        View bar = pillRef.get();
        if (bar == null || hide == pillHidden) return;
        pillHidden = hide;
        bar.animate().cancel();
        if (hide) {
            float off = bar.getHeight() + PILL_BOTTOM_MARGIN * bar.getResources().getDisplayMetrics().density * 2;
            bar.animate().translationY(off).alpha(0f).setDuration(HIDE_MS)
                    .setInterpolator(new AccelerateInterpolator()).start();
        } else {
            bar.animate().translationY(0f).alpha(1f).setDuration(SHOW_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    // ---- cover-screen Reels ----

    private static void fillReels(View root) {
        View info = byName(root, "clips_viewer_media_info_container");
        if (info == null) return; // not in Reels

        hide(byName(root, "clips_viewer_letterbox_background"));
        hide(byName(root, "clips_viewer_letterbox_imageview"));

        // Center-crop every visible video surface to cover its container.
        cropVideos(root);

        float dp = root.getResources().getDisplayMetrics().density;

        // Keep caption/username/rail above the pill + gesture bar.
        if (info.getTag(TAG) == null) {
            info.setTag(TAG, Boolean.TRUE);
            View bar = pillRef.get();
            int pill = bar != null ? bar.getHeight() + (int) (PILL_BOTTOM_MARGIN * dp) : 0;
            info.setPadding(info.getPaddingLeft(), info.getPaddingTop(), info.getPaddingRight(),
                    info.getPaddingBottom() + pill);
        }

        // Size the like/comment/share/save/more icons for a 475dp screen.
        if (info instanceof ViewGroup) sizeUfi((ViewGroup) info, (int) (UFI_ICON * dp), dp);
    }

    private static void cropVideos(View v) {
        if (v.getVisibility() != View.VISIBLE) return;
        if ((v instanceof TextureView || v instanceof SurfaceView) && v.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) v.getParent();
            int vw = v.getWidth(), vh = v.getHeight(), pw = p.getWidth(), ph = p.getHeight();
            if (vw > 0 && vh > 0 && pw > 0 && ph > 0) {
                float s = Math.max((float) pw / vw, (float) ph / vh);
                if (s > 1.01f && s < 1.6f && Math.abs(v.getScaleX() - s) > 0.01f) {
                    p.setClipChildren(true);
                    v.setScaleX(s);
                    v.setScaleY(s);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) cropVideos(g.getChildAt(i));
        }
    }

    private static void sizeUfi(ViewGroup g, int iconPx, float dp) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageView && c.getTag(TAG) == null) {
                String n = entryName(c);
                if (n != null && (n.contains("ufi") || n.contains("like") || n.contains("comment")
                        || n.contains("share") || n.contains("save") || n.contains("more"))) {
                    ViewGroup.LayoutParams lp = c.getLayoutParams();
                    if (lp != null && lp.width > 0 && lp.width < 64 * dp) {
                        c.setTag(TAG, Boolean.TRUE);
                        lp.width = iconPx;
                        lp.height = iconPx;
                        c.setLayoutParams(lp);
                        c.setMinimumWidth((int) (48 * dp)); // tap target
                        c.setMinimumHeight((int) (48 * dp));
                    }
                }
            } else if (c instanceof ViewGroup) {
                sizeUfi((ViewGroup) c, iconPx, dp);
            }
        }
    }

    // ============================================================ hide-on-scroll

    /** Wraps the window callback to read swipe direction without touching Instagram's scroll code. */
    private static final class TouchTracker implements Window.Callback {
        private final Window.Callback d;
        private final float threshold;
        private float lastY, acc;
        private boolean tracking;

        TouchTracker(Window.Callback delegate, Activity a) {
            d = delegate;
            threshold = SCROLL_THRESHOLD * a.getResources().getDisplayMetrics().density;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    View bar = pillRef.get();
                    // Ignore drags that start on the pill itself.
                    tracking = bar == null || pillHidden || e.getRawY() < bar.getY() + bar.getTranslationY();
                    lastY = e.getRawY();
                    acc = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking || e.getPointerCount() > 1) break;
                    float dy = lastY - e.getRawY(); // >0 = finger moving up = content scrolling forward
                    lastY = e.getRawY();
                    if ((dy > 0) != (acc > 0)) acc = 0;
                    acc += dy;
                    if (acc > threshold) setPillHidden(true);
                    else if (acc < -threshold) setPillHidden(false);
                    break;
                default:
                    break;
            }
            return d.dispatchTouchEvent(e);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) { return d.dispatchKeyEvent(e); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return d.dispatchKeyShortcutEvent(e); }
        @Override public boolean dispatchTrackballEvent(MotionEvent e) { return d.dispatchTrackballEvent(e); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return d.dispatchGenericMotionEvent(e); }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return d.dispatchPopulateAccessibilityEvent(e); }
        @Override public View onCreatePanelView(int f) { return d.onCreatePanelView(f); }
        @Override public boolean onCreatePanelMenu(int f, Menu m) { return d.onCreatePanelMenu(f, m); }
        @Override public boolean onPreparePanel(int f, View v, Menu m) { return d.onPreparePanel(f, v, m); }
        @Override public boolean onMenuOpened(int f, Menu m) { return d.onMenuOpened(f, m); }
        @Override public boolean onMenuItemSelected(int f, MenuItem i) { return d.onMenuItemSelected(f, i); }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams a) { d.onWindowAttributesChanged(a); }
        @Override public void onContentChanged() { d.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean h) { d.onWindowFocusChanged(h); }
        @Override public void onAttachedToWindow() { d.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { d.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int f, Menu m) { d.onPanelClosed(f, m); }
        @Override public boolean onSearchRequested() { return d.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent e) { return d.onSearchRequested(e); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c) { return d.onWindowStartingActionMode(c); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int t) { return d.onWindowStartingActionMode(c, t); }
        @Override public void onActionModeStarted(ActionMode m) { d.onActionModeStarted(m); }
        @Override public void onActionModeFinished(ActionMode m) { d.onActionModeFinished(m); }
    }

    // ============================================================ helpers

    private static View byName(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private static String entryName(View v) {
        if (v.getId() == View.NO_ID) return null;
        try { return v.getResources().getResourceEntryName(v.getId()); }
        catch (Resources.NotFoundException e) { return null; }
    }

    private static void hide(View v) {
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }
}
