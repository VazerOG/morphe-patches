package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint

internal object StopVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("stopVideo", "MedialibPlayer.stopVideo"),
)

internal object PlayNextInQueueFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("gapless.seek.next", "playNextInQueue."),
)

internal object AudioVideoToggleFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to update user last selected audio"),
)

internal object PauseVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("pauseVideo", "MedialibPlayer.pauseVideo()"),
)

internal object PlayVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("playVideo", "MedialibPlayer.playVideo()"),
)
