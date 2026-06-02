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

/**
 * Always-on patch.  Returns true from the subtitle-track check so YouTube Music does not
 * auto-enable captions on videos that the user did not explicitly request captions for.
 * The captionsButtonStatus flag is flipped to true on the storyboard renderer call
 * (so the user can still toggle captions on manually) and reset to false on each
 * new-video event.
 */
@SuppressWarnings("unused")
public final class AutoCaptionsPatch {

    private static boolean captionsButtonStatus;

    /**
     * Injection point.  Returning true forces the disable-captions branch.
     */
    public static boolean disableAutoCaptions() {
        return !captionsButtonStatus;
    }

    /**
     * Injection point.
     */
    public static void setCaptionsButtonStatus(boolean status) {
        captionsButtonStatus = status;
    }
}
