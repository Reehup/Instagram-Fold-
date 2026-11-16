package app.jordy.igfold

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXT = "Lapp/jordy/igfold/Fold;"
private const val APP_SHELL = "Lcom/instagram/app/InstagramAppShell;"
private const val INSET_METHOD = "getLargeScreenInsetContentRatio"

val COMPATIBILITY_INSTAGRAM = Compatibility(
    name = "Instagram",
    packageName = "com.instagram.android",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0xE1306C,
    targets = listOf(AppTarget(version = "456.0.0.24.78")),
)

@Suppress("unused")
val zFoldPatch = patch(
    name = "Z Fold layout + battery",
    description = "Floating hide-on-scroll pill, full-bleed Reels on the cover screen, " +
        "two-pane comments and DMs on the inner screen, 60Hz cap.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    extendWith("extensions/extension.mpe")

    execute {
        // 1) Boot the runtime: InstagramAppShell.onCreate() -> Fold.install(this).
        //    InstagramAppShell is unobfuscated and overrides onCreate()V in 456.
        mutableClassDefBy(APP_SHELL).methods
            .first { it.name == "onCreate" && it.parameterTypes.isEmpty() }
            .addInstruction(0, "invoke-static/range {p0 .. p0}, $EXT->install(Landroid/app/Application;)V")

        // 2) Remove the inset card around Reels on the cover screen.
        //    getLargeScreenInsetContentRatio is unobfuscated (3 dex files in 456):
        //    wrap every float return through Fold.insetRatio().
        val targets = mutableListOf<String>()
        classDefForEach { c ->
            if (c.methods.any { it.name == INSET_METHOD && it.returnType == "F" && it.implementation != null }) {
                targets += c.type
            }
        }
        var hooked = 0
        targets.forEach { type ->
            mutableClassDefBy(type).methods
                .filter { it.name == INSET_METHOD && it.returnType == "F" && it.implementation != null }
                .forEach { m ->
                    m.implementation!!.instructions
                        .withIndex()
                        .filter { it.value.opcode == Opcode.RETURN }
                        .map { it.index }
                        .reversed()
                        .forEach { i ->
                            val r = m.getInstruction<OneRegisterInstruction>(i).registerA
                            m.addInstructions(
                                i,
                                """
                                invoke-static/range {v$r .. v$r}, $EXT->insetRatio(F)F
                                move-result v$r
                                """,
                            )
                            hooked++
                        }
                }
        }
        if (hooked == 0) throw IllegalStateException("$INSET_METHOD not found")
    }
}
