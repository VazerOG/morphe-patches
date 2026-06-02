/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.misc.bitrate

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC_9X

@Suppress("unused")
val bitrateDefaultValuePatch = resourcePatch(
    name = "Bitrate default value",
    description = "Sets the default audio bitrate for mobile and Wi-Fi data to \"Always High\" " +
        "in the YouTube Music data saving settings."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC_9X)

    execute {
        document("res/xml/data_saving_settings.xml").use { document ->
            document.getElementsByTagName(
                "com.google.android.apps.youtube.music.ui.preference.PreferenceCategoryCompat"
            ).item(0).childNodes.apply {
                arrayOf("BitrateAudioMobile", "BitrateAudioWiFi").forEach { keySuffix ->
                    for (i in 1 until length) {
                        val view = item(i)
                        if (
                            view.hasAttributes() &&
                            view.attributes.getNamedItem("android:key")
                                .nodeValue.endsWith(keySuffix)
                        ) {
                            view.attributes.getNamedItem("android:defaultValue")
                                .nodeValue = "Always High"
                            break
                        }
                    }
                }
            }
        }
    }
}
