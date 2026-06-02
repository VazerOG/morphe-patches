/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/inotia00/revanced-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.viewerdiscretion

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC_9X
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/ViewerDiscretionDialogPatch;"

@Suppress("unused")
val viewerDiscretionDialogPatch = bytecodePatch(
    name = "Remove viewer discretion dialog",
    description = "Auto-dismisses the dialog that occasionally appears when playing age-restricted " +
        "or content-warning videos so playback continues without user interaction.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC_9X)

    execute {
        CreateDialogFingerprint.method.apply {
            val showDialogIndex = indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.name == "show"
            }
            val dialogRegister = getInstruction<FiveRegisterInstruction>(showDialogIndex).registerC

            addInstruction(
                showDialogIndex + 1,
                "invoke-static { v$dialogRegister }, $EXTENSION_CLASS->confirmDialog(Landroid/app/AlertDialog;)V"
            )
        }
    }
}
