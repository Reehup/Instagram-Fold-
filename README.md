# Instagram Fold patches (Z Fold 8)

TLDR: patch your own stock Instagram 456.0.0.24.78. The repo ships no Meta code. Test on a throwaway account.

## What it does
| Feature | How |
|---|---|
| Floating pill nav | Restyles IG's `fixed_tabbar_view`: 32dp radius, 16dp side margins, 420dp max width on the inner screen, 90% black |
| Hide on scroll | Finger up hides it (220ms), finger down brings it back slowly (420ms). Reads swipe direction at the window level, so it doesn't depend on IG's obfuscated scroll code |
| Full-bleed Reels (cover) | `getLargeScreenInsetContentRatio` returns 0 on the cover, the letterbox views are hidden, the video is center-cropped (~5.5% off top and bottom) |
| Reels rail (cover) | 28dp icons, 48dp tap targets, 12dp edge margin, padded above the pill |
| Two-pane comments and DMs (inner) | Reports 600dp to IG on the 559dp inner screen so its built-in `CommentsTwoPaneLayout` / `DirectTwoPane` turn on |
| Battery | 60Hz cap while IG is open (your drain is screen-on, not background) |

Toggles are at the top of `Fold.java` (`CAP_60HZ`, `FLOATING_PILL`, etc.).

## Layout
- `extensions/.../Fold.java`: runtime code. Compiled clean against API 33.
- `patches/.../FoldPatch.kt`: bytecode/resource patch that wires it in.

## Build (no PC needed)
1. Upload this repo to a new GitHub repo.
2. The **Build patches** action runs on every push. Open the Actions tab, then the latest run, then download the `instagram-fold-patches` artifact. The `.mpp` file is inside the zip.
3. In Morphe Manager, add the `.mpp` as a local patch source, pick the stock 456 APKM, and patch.

PC alternative: `./gradlew :patches:buildAndroid` with `gpr.user`/`gpr.key` (a token with `read:packages`) in `~/.gradle/gradle.properties`.

## Known unknowns (test these first)
- Whether 600dp alone unlocks two-pane, or whether a server flag gates it too.
- Whether content draws under the pill or leaves a strip. IG also ships `floating_tab_bar_*` resources, so a native floating mode may exist to flip on.
- UFI icon matching is name-based and needs a check with Show layout bounds.
