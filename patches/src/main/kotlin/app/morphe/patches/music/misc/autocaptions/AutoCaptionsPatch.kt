/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/inotia00/revanced-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.autocaptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC_9X

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AutoCaptionsPatch;"

@Suppress("unused")
val autoCaptionsPatch = bytecodePatch(
    name = "Disable forced auto captions",
    description = "Prevents YouTube Music from automatically enabling captions on videos that " +
        "do not have a caption track manually selected.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC_9X)

    execute {
        // Always-on: caption return is forced to true unless the captions button was just
        // tapped (tracked by setCaptionsButtonStatus).
        SubtitleTrackFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS->disableAutoCaptions()Z
                    move-result v0
                    if-eqz v0, :disabled
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("disabled", getInstruction(0))
            )
        }

        mapOf(
            StartVideoInformerFingerprint to 0,
            StoryboardRendererDecoderRecommendedLevelFingerprint to 1,
        ).forEach { (fingerprint, enabled) ->
            fingerprint.method.addInstructions(
                0,
                """
                    const/4 v0, 0x$enabled
                    invoke-static {v0}, $EXTENSION_CLASS->setCaptionsButtonStatus(Z)V
                """
            )
        }
    }
}
