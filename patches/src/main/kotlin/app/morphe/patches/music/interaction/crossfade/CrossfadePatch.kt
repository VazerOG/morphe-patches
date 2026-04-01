package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/patches/CrossfadeManager;"

@Suppress("unused")
val crossfadePatch = bytecodePatch(
    name = "Track crossfade",
    description = "Adds a true dual-player crossfade between consecutive tracks.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_crossfade_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_crossfade_enabled"),
                    TextPreference(
                        key = "morphe_music_crossfade_duration",
                        inputType = InputType.NUMBER,
                    ),
                    SwitchPreference("morphe_music_crossfade_advanced_mode"),
                    TextPreference(
                        key = "morphe_music_crossfade_duration_ms",
                        inputType = InputType.NUMBER,
                    ),
                    SwitchPreference("morphe_music_crossfade_session_control"),
                    TextPreference(
                        key = "morphe_music_crossfade_long_press_duration",
                        inputType = InputType.NUMBER,
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_crossfade_credit",
                        summaryKey = "morphe_music_crossfade_credit_summary",
                    ),
                ),
            ),
        )

        StopVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1}, $EXTENSION_CLASS_DESCRIPTOR->onBeforeStopVideo(Ljava/lang/Object;I)V
            """,
        )

        PlayNextInQueueFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->onBeforePlayNext(Ljava/lang/Object;)V
            """,
        )

        AudioVideoToggleFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockVideoToggle(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :allow_toggle
                return-void
                :allow_toggle
                nop
            """,
        )

        PauseVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->onPauseVideo()V
            """,
        )

        PlayVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->onPlayVideo()V
            """,
        )
    }
}
