/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/inotia00/revanced-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.imageregion

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.imageurlhook.addImageURLHook
import app.morphe.patches.music.misc.imageurlhook.cronetImageURLHookPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC_9X

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/BypassImageRegionRestrictionsPatch;"

@Suppress("unused")
val bypassImageRegionRestrictionsPatch = bytecodePatch(
    name = "Bypass image region restrictions",
    description = "Uses a different host for user avatar and channel images so they load correctly " +
        "in regions where the default host is blocked.",
) {
    dependsOn(
        sharedExtensionPatch,
        cronetImageURLHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC_9X)

    execute {
        // Always-on: no setting check.  A priority hook is not needed, as the image URLs of interest
        // are not modified by any other patch in this repo.
        addImageURLHook(EXTENSION_CLASS)
    }
}
