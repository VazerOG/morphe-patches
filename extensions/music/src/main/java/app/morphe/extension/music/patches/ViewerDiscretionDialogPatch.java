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

import android.app.AlertDialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

/**
 * Always-on patch.  Called immediately after {@link AlertDialog#show()} on the viewer
 * discretion confirmation dialog.  Resizes the window to 0x0, clears the dim-behind
 * flag, then programmatically clicks the positive button so playback continues
 * without user interaction.
 */
@SuppressWarnings("unused")
public final class ViewerDiscretionDialogPatch {

    /**
     * Injection point.
     * <p>
     * The {@link AlertDialog#getButton(int)} method must be used after {@link AlertDialog#show()}
     * is called.  Otherwise {@link AlertDialog#getButton(int)} will always return null.
     * <a href="https://stackoverflow.com/a/4604145">Reference</a>
     */
    public static void confirmDialog(final AlertDialog dialog) {
        // This method is called after AlertDialog#show(),
        // so we need to hide the AlertDialog before pressing the positive button.
        final Window window = dialog.getWindow();
        final Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (window != null && button != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.height = 0;
            params.width = 0;

            // Change the size of AlertDialog to 0.
            window.setAttributes(params);

            // Disable AlertDialog's background dim.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            button.callOnClick();
        }
    }
}
