/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/inotia00/revanced-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Always-on patch.  Rewrites known YouTube static image hosts to a region-agnostic
 * domain so user avatars and channel images load in regions where the original host
 * is blocked.
 */
@SuppressWarnings("unused")
public final class BypassImageRegionRestrictionsPatch {

    private static final String REPLACEMENT_IMAGE_DOMAIN = "https://yt4.ggpht.com";

    /**
     * YouTube static images' domain.  Includes user and channel avatar images and community
     * post images.  Pattern mirrors RVX's broader matcher to cover all host shards.
     */
    private static final Pattern YOUTUBE_STATIC_IMAGE_DOMAIN_PATTERN = Pattern.compile(
            "^https://(ap[1-2]|gm[1-4]|gz0|(cp|ci|gp|lh)[3-6]|sp[1-3]|yt[3-4]|(play|ccp)-lh)\\.(ggpht|googleusercontent)\\.com"
    );

    /**
     * Injection point.  Called off the main thread and by multiple threads at the same time.
     *
     * @param originalUrl Image URL for all image URLs loaded.
     */
    public static String overrideImageURL(String originalUrl) {
        try {
            final String replacement = YOUTUBE_STATIC_IMAGE_DOMAIN_PATTERN
                    .matcher(originalUrl).replaceFirst(REPLACEMENT_IMAGE_DOMAIN);
            if (!replacement.equals(originalUrl)) {
                Logger.printDebug(() -> "Replaced: '" + originalUrl + "' with: '" + replacement + "'");
            }
            return replacement;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideImageURL failure", ex);
        }
        return originalUrl;
    }
}
