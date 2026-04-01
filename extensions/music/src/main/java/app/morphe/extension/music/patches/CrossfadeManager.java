package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import android.annotation.SuppressLint;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.music.settings.Settings;

/**
 * Player-swap crossfade manager for YouTube Music.
 *
 * Strategy: when a skip-next is detected (stopVideo reason=5), we
 * preserve the OLD ExoPlayer (which keeps playing the outgoing track)
 * and create a NEW ExoPlayer via YT Music's own factory method
 * (atih.a) so it has full DRM / DataSource configuration.  We swap
 * athu.h to the new player so the subsequent loadVideo flow uses it.
 * Once the new track reaches STATE_READY we run an equal-power
 * crossfade, then release the old player.
 *
 * Obfuscated name mapping (JADX → real):
 *
 *   atad.c              → field "c"  (atxb player interface)
 *   atux.a              → field "a"  (atxb delegate)
 *   athu.h              → field "h"  (ExoPlayer instance)
 *   athu.j              → field "j"  (atgd session)
 *   athu.i              → field "i"  (atis / cqf LoadControl)
 *   atgd.a              → field "a"  (atih player factory)
 *   atih.a(athu,cqf,int) → method "a" (build ExoPlayer)
 *   bxk.I(float)        → method "I" (setVolume)
 *   bxk.r()             → method "r" (getPlaybackState)
 *   ExoPlayer.P()       → method "P" (release)
 */
@SuppressLint({"MissingPermission", "PrivateApi", "DiscouragedApi"})
@SuppressWarnings("unused")
public class CrossfadeManager {

    private static final String TAG = "Morphe_Crossfade";

    /**
     * Fade curve profiles available for crossfade.
     * Uses switch instead of abstract methods to avoid anonymous inner classes,
     * which break Morphe's EnumSetting (getClass().getEnumConstants() returns null
     * for anonymous enum subclasses).
     */
    public enum FadeCurve {
        EQUAL_POWER,
        EASE_OUT_CUBIC,
        EASE_OUT_QUAD,
        SMOOTHSTEP;

        public float out(float t) {
            switch (this) {
                case EASE_OUT_CUBIC: return 1.0f - t * t * t;
                case EASE_OUT_QUAD:  return (1.0f - t) * (1.0f - t);
                case SMOOTHSTEP:    return 1.0f - (3.0f * t * t - 2.0f * t * t * t);
                default:            return (float) Math.cos(t * Math.PI / 2.0);
            }
        }

        public float in(float t) {
            if (this == SMOOTHSTEP) return 3.0f * t * t - 2.0f * t * t * t;
            return (float) Math.sin(t * Math.PI / 2.0);
        }
    }

    private static volatile boolean sessionPaused = false;

    /**
     * Tracks whether the player is currently in video mode.
     * Updated by shouldBlockVideoToggle (fires before every audio/video toggle).
     */
    private static volatile boolean inVideoMode = false;

    /**
     * Set true by shouldBlockVideoToggle when allowing a video→audio toggle.
     * That toggle triggers stopVideo(reason=5) which is indistinguishable
     * from a real track skip.  This flag suppresses the next crossfade so
     * we don't create a duplicate player for the same song.
     */
    private static volatile boolean skipNextCrossfade = false;

    private static volatile boolean crossfadeInProgress = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int TICK_MS = 50;
    private static final int READY_POLL_MS = 100;
    private static final int READY_TIMEOUT_MS = 10000;
    private static final int STATE_READY = 3;
    private static final int REASON_DIRECTOR_RESET = 5;
    private static final long AUTO_ADVANCE_THRESHOLD_MS = 5000;
    private static final long MONITOR_POLL_MS = 100;

    private static volatile Object oldPlayer = null;
    private static volatile Object activeDll = null;
    private static volatile Object crossfadeInPlayer = null;
    private static volatile Object crossfadeOutPlayer = null;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static Runnable autoAdvanceMonitorRunnable = null;

    private static int playersCreated = 0;
    private static int playersReleased = 0;
    private static final java.util.List<WeakReference<View>> longPressRefs =
            new java.util.ArrayList<>();

    // ------------------------------------------------------------------ //
    //  Public hook: stopVideo (manual skip-next)                          //
    // ------------------------------------------------------------------ //

    private static int lastLoggedReason = -1;
    private static int suppressedReasonCount = 0;

    public static void onBeforeStopVideo(Object atadInstance, int reason) {
        lastAtadRef = new WeakReference<>(atadInstance);
        tryAttachLongPressHandler();

        if (reason != REASON_DIRECTOR_RESET) {
            if (reason == lastLoggedReason) {
                suppressedReasonCount++;
            } else {
                if (suppressedReasonCount > 0) {
                    Log.d(TAG, "  (suppressed " + suppressedReasonCount + " duplicate reason=" + lastLoggedReason + " entries)");
                }
                Log.d(TAG, "stopVideo reason=" + reason + " — not a skip, ignoring");
                lastLoggedReason = reason;
                suppressedReasonCount = 0;
            }
            return;
        }
        lastLoggedReason = -1;
        suppressedReasonCount = 0;

        if (skipNextCrossfade) {
            skipNextCrossfade = false;
            Log.d(TAG, "stopVideo(5): skip — video→audio toggle (same song)");
            return;
        }

        if (!isEnabled() || sessionPaused || getCrossfadeDurationMs() <= 0) {
            Log.d(TAG, "stopVideo(5): skip [enabled=" + isEnabled()
                    + " paused=" + sessionPaused + " inVideo=" + inVideoMode + "]");
            return;
        }

        if (crossfadeInProgress) {
            Log.d(TAG, "stopVideo(5): skip — crossfade already in progress");
            return;
        }

        try {
            Object athu = getAthuFromAtad(atadInstance);
            if (athu == null) {
                Log.e(TAG, "Could not find athu from atad");
                return;
            }

            Object currentExo = getFieldValue(athu, "h");
            if (currentExo == null) {
                Log.e(TAG, "athu.h (ExoPlayer) is null");
                return;
            }

            boolean isAutoAdvance = false;
            try {
                long pos = callLongMethod(currentExo, "v");
                long duration = callLongMethod(currentExo, "w");
                long remaining = (duration > 0) ? duration - pos : Long.MAX_VALUE;
                isAutoAdvance = duration > 0 && remaining >= 0 && remaining < AUTO_ADVANCE_THRESHOLD_MS;
                Log.d(TAG, "stopVideo(5): pos=" + pos + "ms dur=" + duration
                        + "ms remaining=" + remaining
                        + "ms → " + (isAutoAdvance ? "AUTO-ADVANCE" : "MANUAL SKIP"));
            } catch (Exception e) {
                Log.w(TAG, "Could not read position/duration, assuming manual skip", e);
            }

            if (isAutoAdvance && !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
                Log.d(TAG, "stopVideo(5): skip — auto-advance crossfade disabled");
                return;
            }
            if (!isAutoAdvance && !Settings.CROSSFADE_ON_SKIP.get()) {
                Log.d(TAG, "stopVideo(5): skip — manual skip crossfade disabled");
                return;
            }

            Log.d(TAG, "stopVideo(5): STARTING crossfade [enabled=" + isEnabled()
                    + " paused=" + sessionPaused + " inVideo=" + inVideoMode + "]");

            int currentState = callIntMethod(currentExo, "r");
            Log.d(TAG, "Current player state=" + currentState
                    + " class=" + currentExo.getClass().getName());

            Object atgd = getFieldValue(athu, "j");
            if (atgd == null) {
                Log.e(TAG, "athu.j (atgd session) is null");
                return;
            }

            Object atih = getFieldValue(atgd, "a");
            if (atih == null) {
                Log.e(TAG, "atgd.a (atih factory) is null");
                return;
            }

            Object cqf = getFieldValue(athu, "i");
            if (cqf == null) {
                Log.e(TAG, "athu.i (cqf/atis) is null");
                return;
            }

            Object crz = getFieldValue(athu, "c");
            if (crz == null) {
                Log.e(TAG, "athu.c (crz/cup) is null");
                return;
            }

            Object dll = getFieldValue(athu, "w");
            if (dll == null) {
                Log.e(TAG, "athu.w (atjx/dll) is null");
                return;
            }
            activeDll = dll;

            Object oldCrzBxk = tryGetField(crz, "g");
            Object oldDllCqb = tryGetField(dll, "h");
            Object oldDllDlt = tryGetField(dll, "i");
            Log.d(TAG, "Pre-factory shared state: crz.g=" + (oldCrzBxk != null)
                    + " dll.h=" + (oldDllCqb != null) + " dll.i=" + (oldDllDlt != null));
            setFieldValue(crz, "g", null);
            setFieldValue(dll, "h", null);

            Object newExo = createPlayerViaFactory(atih, athu, cqf);
            if (newExo == null) {
                Log.e(TAG, "Factory returned null — restoring and aborting");
                setFieldValue(crz, "g", oldCrzBxk);
                setFieldValue(dll, "h", oldDllCqb);
                return;
            }

            Object postCrzBxk = tryGetField(crz, "g");
            Object postDllCqb = tryGetField(dll, "h");
            Object postDllDlt = tryGetField(dll, "i");
            Log.d(TAG, "Post-factory shared state: crz.g=" + (postCrzBxk != null)
                    + " dll.h=" + (postDllCqb != null) + " dll.i=" + (postDllDlt != null)
                    + " newExo=" + System.identityHashCode(newExo));
            if (postCrzBxk == null) {
                Log.e(TAG, "Factory failed to set crz.g (bxk) — aborting");
                setFieldValue(crz, "g", oldCrzBxk);
                setFieldValue(dll, "h", oldDllCqb);
                return;
            }
            if (postDllCqb == null) {
                Log.e(TAG, "Factory failed to set dll.h (cqb) — aborting");
                setFieldValue(crz, "g", oldCrzBxk);
                setFieldValue(dll, "h", oldDllCqb);
                return;
            }

            findMethod(newExo, "I", float.class).invoke(newExo, 0.0f);

            setFieldValue(athu, "h", newExo);
            Log.d(TAG, "Swapped athu.h → new player");

            // Re-register athu's UI listener (field "b", cnd) on the new
            // player.  Without this, play/pause state changes from the new
            // player don't reach the UI — the button stays stuck on the
            // old player's last state.
            transferPlayerListener(athu, currentExo, newExo);

            Object atix = tryGetField(athu, "z");
            if (atix != null) {
                setFieldValue(atix, "e", newExo);
                Log.d(TAG, "Updated atix.e → new player (video surface target)");
            }

            releaseOld();
            oldPlayer = currentExo;
            crossfadeOutPlayer = currentExo;
            crossfadeInPlayer = newExo;
            crossfadeInProgress = true;

            Log.d(TAG, "Old player preserved (keeps playing), polling for new track ready");
            pollForNewTrackReady(newExo, currentExo);

        } catch (Exception e) {
            Log.e(TAG, "onBeforeStopVideo error", e);
            releaseOld();
            crossfadeInProgress = false;
        }
    }

    // ------------------------------------------------------------------ //
    //  Public hook: playNextInQueue (gapless auto-advance)                //
    // ------------------------------------------------------------------ //

    public static void onBeforePlayNext(Object athuInstance) {
        Log.d(TAG, "onBeforePlayNext called");
        tryAttachLongPressHandler();

        if (!isEnabled() || sessionPaused || getCrossfadeDurationMs() <= 0 || crossfadeInProgress) {
            return;
        }

        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            Log.d(TAG, "PlayNext: skip — auto-advance crossfade disabled");
            return;
        }

        try {
            Object currentExo = getFieldValue(athuInstance, "h");
            if (currentExo == null) return;

            int currentState = callIntMethod(currentExo, "r");
            Log.d(TAG, "PlayNext: current player state=" + currentState);

            Object atgd = getFieldValue(athuInstance, "j");
            if (atgd == null) return;
            Object atih = getFieldValue(atgd, "a");
            if (atih == null) return;
            Object cqf = getFieldValue(athuInstance, "i");
            if (cqf == null) return;

            Object crz = getFieldValue(athuInstance, "c");
            if (crz == null) return;
            Object dll = getFieldValue(athuInstance, "w");
            if (dll == null) return;
            activeDll = dll;

            Object oldCrzBxk = tryGetField(crz, "g");
            Object oldDllCqb = tryGetField(dll, "h");
            setFieldValue(crz, "g", null);
            setFieldValue(dll, "h", null);

            Object newExo = createPlayerViaFactory(atih, athuInstance, cqf);
            if (newExo == null) {
                setFieldValue(crz, "g", oldCrzBxk);
                setFieldValue(dll, "h", oldDllCqb);
                return;
            }

            Object postCrzBxk = tryGetField(crz, "g");
            Object postDllCqb = tryGetField(dll, "h");
            Log.d(TAG, "PlayNext post-factory: crz.g=" + (postCrzBxk != null)
                    + " dll.h=" + (postDllCqb != null));
            if (postCrzBxk == null) {
                Log.e(TAG, "PlayNext: factory failed to set crz.g — aborting");
                setFieldValue(crz, "g", oldCrzBxk);
                setFieldValue(dll, "h", oldDllCqb);
                return;
            }
            if (postDllCqb == null) {
                Log.e(TAG, "PlayNext: factory failed to set dll.h — aborting");
                setFieldValue(crz, "g", oldCrzBxk);
                setFieldValue(dll, "h", oldDllCqb);
                return;
            }

            findMethod(newExo, "I", float.class).invoke(newExo, 0.0f);

            setFieldValue(athuInstance, "h", newExo);
            Log.d(TAG, "PlayNext: swapped athu.h → new player");

            transferPlayerListener(athuInstance, currentExo, newExo);

            Object atix = tryGetField(athuInstance, "z");
            if (atix != null) {
                setFieldValue(atix, "e", newExo);
                Log.d(TAG, "PlayNext: updated atix.e → new player (video surface target)");
            }

            releaseOld();
            oldPlayer = currentExo;
            crossfadeOutPlayer = currentExo;
            crossfadeInPlayer = newExo;
            crossfadeInProgress = true;

            Log.d(TAG, "PlayNext: old player preserved, polling for new track ready");
            pollForNewTrackReady(newExo, currentExo);

        } catch (Exception e) {
            Log.e(TAG, "onBeforePlayNext error", e);
            releaseOld();
            crossfadeInProgress = false;
        }
    }

    // ------------------------------------------------------------------ //
    //  Public hooks: pauseVideo / playVideo (MedialibPlayer layer)        //
    // ------------------------------------------------------------------ //

    private static long lastPauseEventMs = 0;
    private static long lastPlayEventMs = 0;
    private static final long EVENT_DEDUP_WINDOW_MS = 100;

    /**
     * Hooked at the top of atad.m15631I (MedialibPlayer.pauseVideo).
     * Returns true to BLOCK the pause (return-void in smali), false to allow.
     *
     * During an auto-advance crossfade the old track reaches ENDED, which
     * causes YTM to call pauseVideo().  If we let that through it pauses
     * the NEW player (athu.h was already swapped) and kills playback.
     * We detect this by checking whether the outgoing player is ENDED.
     *
     * A genuine user pause (old player still READY) aborts the crossfade
     * and lets the pause proceed normally.
     */
    public static boolean onPauseVideo() {
        long now = System.currentTimeMillis();
        if (now - lastPauseEventMs < EVENT_DEDUP_WINDOW_MS) return false;
        lastPauseEventMs = now;

        if (!crossfadeInProgress) {
            Log.d(TAG, "onPauseVideo [crossfading=false] — allowing");
            return false;
        }

        int outState = -1;
        try {
            Object out = crossfadeOutPlayer;
            if (out != null) outState = callIntMethod(out, "r");
        } catch (Exception ignored) {}

        if (outState == 4) {
            Log.d(TAG, "onPauseVideo [crossfading=true, outState=ENDED] — BLOCKING pause (auto-advance expected)");
            return true;
        }

        Log.d(TAG, "onPauseVideo [crossfading=true, outState=" + outState + "] — user pause, aborting crossfade");
        abortCrossfadeNow();
        return false;
    }

    /**
     * Hooked at the top of atad.m15645p (MedialibPlayer.playVideo).
     * Captures the atad instance for the auto-advance monitor and starts it if idle.
     */
    public static void onPlayVideo(Object atadInstance) {
        long now = System.currentTimeMillis();
        if (now - lastPlayEventMs < EVENT_DEDUP_WINDOW_MS) return;
        lastPlayEventMs = now;

        if (atadInstance != null) {
            lastAtadRef = new WeakReference<>(atadInstance);
        }

        Log.d(TAG, "onPlayVideo [crossfading=" + crossfadeInProgress
                + ", atad=" + (atadInstance != null) + "]");
        if (!crossfadeInProgress) {
            startAutoAdvanceMonitor();
        }
    }

    // ------------------------------------------------------------------ //
    //  Poller: waits for new track to reach STATE_READY                   //
    // ------------------------------------------------------------------ //

    private static int lastPollState = -1;

    private static void pollForNewTrackReady(final Object newPlayer, final Object outPlayer) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        lastPollState = -1;

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!crossfadeInProgress) {
                    return;
                }

                try {
                    int state = callIntMethod(newPlayer, "r");
                    if (state == STATE_READY) {
                        Log.d(TAG, "New track READY — starting crossfade");
                        animateCrossfade(outPlayer, newPlayer);
                        return;
                    }

                    if (state == 4) {
                        Log.e(TAG, "New player ENDED unexpectedly — aborting");
                        releaseOld();
                        crossfadeInProgress = false;
                        try {
                            findMethod(newPlayer, "I", float.class)
                                    .invoke(newPlayer, 1.0f);
                        } catch (Exception ignored) {}
                        return;
                    }

                    if (state != lastPollState) {
                        Log.d(TAG, "Poll: state → " + state);
                        lastPollState = state;
                    }

                    if (System.currentTimeMillis() > deadline) {
                        Log.e(TAG, "Timeout waiting for new track");
                        releaseOld();
                        crossfadeInProgress = false;
                        try {
                            findMethod(newPlayer, "I", float.class)
                                    .invoke(newPlayer, 1.0f);
                        } catch (Exception ignored) {}
                        return;
                    }

                    mainHandler.postDelayed(this, READY_POLL_MS);
                } catch (Exception e) {
                    Log.e(TAG, "Poll error", e);
                    releaseOld();
                    crossfadeInProgress = false;
                }
            }
        }, READY_POLL_MS);
    }

    // ------------------------------------------------------------------ //
    //  Auto-advance: position monitor & timed crossfade                   //
    // ------------------------------------------------------------------ //

    private static void startAutoAdvanceMonitor() {
        stopAutoAdvanceMonitor();
        if (!isEnabled() || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) return;

        autoAdvanceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isEnabled() || sessionPaused || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()
                        || crossfadeInProgress) {
                    return;
                }

                Object atad = lastAtadRef.get();
                if (atad == null) {
                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                    return;
                }

                try {
                    Object athu = getAthuFromAtadQuiet(atad);
                    if (athu == null) { mainHandler.postDelayed(this, MONITOR_POLL_MS); return; }
                    Object exo = getFieldValue(athu, "h");
                    if (exo == null) { mainHandler.postDelayed(this, MONITOR_POLL_MS); return; }

                    int state = callIntMethod(exo, "r");
                    if (state != STATE_READY) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    long pos = callLongMethod(exo, "v");
                    long dur = callLongMethod(exo, "w");
                    if (dur <= 0) { mainHandler.postDelayed(this, MONITOR_POLL_MS); return; }

                    long remaining = dur - pos;
                    long fadeDuration = getCrossfadeDurationMs();

                    if (remaining % 5000 < MONITOR_POLL_MS) {
                        Log.d(TAG, "Auto-advance monitor: pos=" + pos
                                + "ms dur=" + dur + "ms remaining=" + remaining
                                + "ms trigger@" + fadeDuration + "ms");
                    }

                    if (dur <= fadeDuration) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    if (remaining <= fadeDuration && remaining > 0) {
                        Log.d(TAG, "Auto-advance: triggering playNextInQueue at remaining=" + remaining
                                + "ms (fadeDuration=" + fadeDuration + "ms)");
                        stopAutoAdvanceMonitor();
                        try {
                            findMethod(atad, "o").invoke(atad);
                        } catch (Exception e) {
                            Log.w(TAG, "playNextInQueue threw (queue may have advanced): "
                                    + e.getMessage());
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                } catch (Exception e) {
                    Log.w(TAG, "Auto-advance monitor error", e);
                    mainHandler.postDelayed(this, MONITOR_POLL_MS * 2);
                }
            }
        };
        mainHandler.postDelayed(autoAdvanceMonitorRunnable, MONITOR_POLL_MS);
        Log.d(TAG, "Auto-advance monitor started");
    }

    private static void stopAutoAdvanceMonitor() {
        if (autoAdvanceMonitorRunnable != null) {
            mainHandler.removeCallbacks(autoAdvanceMonitorRunnable);
            autoAdvanceMonitorRunnable = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Volume animation (equal-power curve)                               //
    // ------------------------------------------------------------------ //

    /**
     * Immediately finishes any in-progress crossfade: snaps the incoming
     * player to full volume and releases the outgoing player.  Called by
     * toggleSessionPause so the user hears no lingering audio.
     */
    private static void abortCrossfadeNow() {
        if (!crossfadeInProgress) return;
        Log.d(TAG, "abortCrossfadeNow: snapping volumes & releasing");

        Object inp = crossfadeInPlayer;
        if (inp != null) {
            try {
                findMethod(inp, "I", float.class).invoke(inp, 1.0f);
            } catch (Exception ignored) {}
        }
        crossfadeInPlayer = null;
        crossfadeOutPlayer = null;
        releaseOld();
        crossfadeInProgress = false;
    }

    private static void animateCrossfade(final Object outPlayer, final Object inPlayer) {
        if (outPlayer == null) {
            Log.w(TAG, "No old player to crossfade from");
            crossfadeInProgress = false;
            try {
                findMethod(inPlayer, "I", float.class).invoke(inPlayer, 1.0f);
            } catch (Exception ignored) {}
            return;
        }

        crossfadeOutPlayer = outPlayer;
        crossfadeInPlayer = inPlayer;

        try {
            findMethod(inPlayer, "E", boolean.class).invoke(inPlayer, true);
        } catch (Exception ignored) {}

        final long startTime = System.currentTimeMillis();
        final long duration = getCrossfadeDurationMs();

        Log.d(TAG, "Crossfade animation started, duration=" + duration + "ms");

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!crossfadeInProgress) {
                    return;
                }

                long elapsed = System.currentTimeMillis() - startTime;
                float t = Math.min(1.0f, (float) elapsed / duration);

                FadeCurve curve = Settings.CROSSFADE_CURVE.get();
                float outVol = curve.out(t);
                float inVol  = curve.in(t);

                try {
                    findMethod(outPlayer, "I", float.class).invoke(outPlayer, outVol);
                    findMethod(inPlayer, "I", float.class).invoke(inPlayer, inVol);
                    if (elapsed % 500 < TICK_MS) {
                        int outState = callIntMethod(outPlayer, "r");
                        int inState = callIntMethod(inPlayer, "r");
                        Log.d(TAG, String.format("t=%.2f outVol=%.2f(st=%d) inVol=%.2f(st=%d)",
                                t, outVol, outState, inVol, inState));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Volume tick error", e);
                }

                if (t < 1.0f) {
                    mainHandler.postDelayed(this, TICK_MS);
                } else {
                    Log.d(TAG, "Crossfade complete — new track is audio-only");
                    inVideoMode = false;
                    crossfadeInPlayer = null;
                    crossfadeOutPlayer = null;
                    try {
                        findMethod(inPlayer, "I", float.class).invoke(inPlayer, 1.0f);
                    } catch (Exception ignored) {}
                    releaseOld();
                    crossfadeInProgress = false;
                    startAutoAdvanceMonitor();
                }
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Player creation via YTM factory                                    //
    // ------------------------------------------------------------------ //

    private static Object createPlayerViaFactory(Object atih, Object athu, Object cqf) {
        try {
            Class<?> athuClass = Class.forName("athu");
            Class<?> cqfClass  = Class.forName("cqf");

            Method factoryMethod = atih.getClass().getDeclaredMethod("a",
                    athuClass, cqfClass, int.class);
            factoryMethod.setAccessible(true);

            Object player = factoryMethod.invoke(atih, athu, cqf, 0);
            if (player != null) {
                playersCreated++;
                Log.d(TAG, "Factory created player @" + System.identityHashCode(player)
                        + " [created=" + playersCreated + " released=" + playersReleased
                        + " outstanding=" + (playersCreated - playersReleased) + "]");
            }
            return player;
        } catch (Exception e) {
            Log.e(TAG, "createPlayerViaFactory failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Listener transfer                                                  //
    // ------------------------------------------------------------------ //

    /**
     * Transfers ALL cnd listeners from the old ExoPlayer's listener set
     * (field K, CopyOnWriteArraySet) to the new player.  Each cpp has
     * its own internal listener at field P (always present), plus any
     * external listeners (UI callbacks, athu.b when non-null, etc.).
     * We skip the old player's own P listener (it belongs to the old
     * player) and move everything else.
     */
    @SuppressWarnings("unchecked")
    private static void transferPlayerListener(Object athu, Object oldExo, Object newExo) {
        try {
            Object oldSet = tryGetField(oldExo, "K");
            if (oldSet == null) {
                Log.d(TAG, "Old player has no listener set (K)");
                return;
            }

            Object oldInternalListener = tryGetField(oldExo, "P");

            java.util.Set<Object> set = (java.util.Set<Object>) oldSet;
            int transferred = 0;
            for (Object listener : set) {
                if (listener == oldInternalListener) continue;
                findMethod(newExo, "O", Class.forName("cnd"))
                        .invoke(newExo, listener);
                transferred++;
            }

            // Also check athu.b in case it wasn't in the old set
            Object athuListener = tryGetField(athu, "b");
            if (athuListener != null && !set.contains(athuListener)) {
                findMethod(newExo, "O", Class.forName("cnd"))
                        .invoke(newExo, athuListener);
                transferred++;
            }

            Log.d(TAG, "Transferred " + transferred + " listener(s) → new player");
        } catch (Exception e) {
            Log.w(TAG, "transferPlayerListener failed", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  athu traversal from atad                                           //
    // ------------------------------------------------------------------ //

    /** Quiet variant for monitor polling — no traversal logging. */
    private static Object getAthuFromAtadQuiet(Object atadInstance) {
        try {
            Object atxb = getFieldValue(atadInstance, "c");
            if (atxb == null) return null;
            for (int i = 0; i < 10; i++) {
                Object delegate = tryGetField(atxb, "a");
                if (delegate == null || delegate == atxb) break;
                atxb = delegate;
            }
            if (tryGetField(atxb, "h") != null && tryGetField(atxb, "j") != null) {
                return atxb;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Walks atad.c → atux.a → ... → athu to reach the innermost
     * player coordinator that holds the ExoPlayer reference at field h.
     */
    private static Object getAthuFromAtad(Object atadInstance) {
        try {
            Object atxb = getFieldValue(atadInstance, "c");
            if (atxb == null) {
                Log.e(TAG, "atad.c is null");
                return null;
            }

            int depth = 0;
            for (int i = 0; i < 10; i++) {
                Object delegate = tryGetField(atxb, "a");
                if (delegate == null || delegate == atxb) break;
                atxb = delegate;
                depth++;
            }

            Log.d(TAG, "Traversed " + depth + " delegates → " + atxb.getClass().getName());

            if (tryGetField(atxb, "h") != null && tryGetField(atxb, "j") != null) {
                return atxb;
            }

            Log.e(TAG, "Innermost class doesn't look like athu: " + atxb.getClass().getName());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "getAthuFromAtad error", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Old player lifecycle                                               //
    // ------------------------------------------------------------------ //

    private static void releaseOld() {
        Object p = oldPlayer;
        oldPlayer = null;
        if (p == null) return;

        playersReleased++;
        Log.d(TAG, "releaseOld: @" + System.identityHashCode(p)
                + " [created=" + playersCreated + " released=" + playersReleased
                + " outstanding=" + (playersCreated - playersReleased) + "]");

        // 1. Save dll.h/dll.i (shared fields the new player needs).
        //    cqb shutdown (message 7) runs synchronously inside P()
        //    and clears these — we restore them after.
        Object dll = activeDll;
        Object savedH = null, savedI = null;
        if (dll != null) {
            savedH = tryGetField(dll, "h");
            savedI = tryGetField(dll, "i");
        }

        // 2. Null out dlt (field "O") on the old player.
        //    P() calls dlt.h(crz) then crz.U() — nulling dlt causes
        //    an NPE that prevents crz.U() from tearing down the shared
        //    cup listener bus.
        setFieldValue(p, "O", null);

        // 3. Release — do NOT call E(false) or I(0.0f) beforehand.
        //    The crossfade animation already faded the old player to
        //    volume 0, and P() handles internal shutdown via cqb
        //    message 7.  Calling E(false) triggers onPaused() up the
        //    framework chain which flips the UI play/pause button to
        //    the wrong state.  (VazerOG approach: just null dlt + P())
        safeRelease(p);

        // 4. Restore dll.h/dll.i that message 7 cleared
        if (dll != null) {
            Object postH = tryGetField(dll, "h");
            Object postI = tryGetField(dll, "i");
            if (savedH != null && postH == null) {
                setFieldValue(dll, "h", savedH);
                Log.d(TAG, "releaseOld: restored dll.h");
            }
            if (savedI != null && postI == null) {
                setFieldValue(dll, "i", savedI);
                Log.d(TAG, "releaseOld: restored dll.i");
            }
        }
    }




    // ------------------------------------------------------------------ //
    //  Settings                                                           //
    // ------------------------------------------------------------------ //

    public static boolean isSessionPaused() {
        return sessionPaused;
    }

    public static void toggleSessionPause() {
        sessionPaused = !sessionPaused;
        Log.d(TAG, "Session " + (sessionPaused ? "PAUSED" : "RESUMED")
                + " [inVideo=" + inVideoMode + " inProgress=" + crossfadeInProgress + "]");

        if (sessionPaused) {
            abortCrossfadeNow();
            stopAutoAdvanceMonitor();
        } else {
            startAutoAdvanceMonitor();
        }

        Context ctx = getAppContext();
        if (ctx != null) {
            try {
                Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    vib.vibrate(100);
                }
            } catch (Exception ignored) {}

            String msg = sessionPaused
                    ? "Crossfade paused for this session"
                    : "Crossfade resumed";
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Returns true when crossfade should actually fire:
     * master switch is on AND not session-paused.
     */
    public static boolean isCrossfadeActive() {
        return isEnabled() && !sessionPaused;
    }

    /**
     * Called by the bytecode hook on the audio/video toggle (nba.c).
     * This hook fires BEFORE the toggle method runs.
     *
     * Blocks audio→video transitions when crossfade is active (enabled
     * AND not session-paused), because the toggle triggers stopVideo(reason=5)
     * which is indistinguishable from a real track skip.  If crossfade fires
     * on a mode toggle it creates an audio-only player for the same song,
     * cumulatively corrupting the video pipeline.
     *
     * Video→audio transitions are always allowed.
     */
    public static boolean shouldBlockVideoToggle(Object nba) {
        tryAttachLongPressHandler();
        try {
            Object nlwInstance = getFieldValue(nba, "a");
            Object currentState = findMethod(nlwInstance, "a").invoke(nlwInstance);

            java.lang.reflect.Method fMethod = null;
            for (java.lang.reflect.Method m : nlwInstance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("f") && m.getParameterCount() == 1
                        && m.getReturnType() == boolean.class) {
                    fMethod = m;
                    break;
                }
            }
            if (fMethod == null) return false;
            fMethod.setAccessible(true);
            boolean isAudioMode = (boolean) fMethod.invoke(null, currentState);

            Log.d(TAG, "videoToggle: isAudioMode=" + isAudioMode
                    + " enabled=" + isEnabled() + " paused=" + sessionPaused
                    + " inVideoMode(before)=" + inVideoMode);

            if (!isEnabled() || sessionPaused) {
                inVideoMode = isAudioMode;
                if (!isAudioMode) {
                    // video→audio while crossfade inactive — the resulting
                    // stopVideo(5) is a mode switch, not a track skip
                    skipNextCrossfade = true;
                }
                Log.d(TAG, "videoToggle → ALLOW (crossfade inactive), inVideoMode="
                        + inVideoMode + " skipNext=" + skipNextCrossfade);
                return false;
            }

            if (isAudioMode) {
                // Audio→video while crossfade active — BLOCK.
                // Don't update inVideoMode: toggle is blocked, state unchanged.
                Log.d(TAG, "videoToggle → BLOCK (audio→video while crossfade active)");
                showVideoBlockedToast();
                return true;
            }

            // Video→audio while crossfade active — allow, but suppress
            // the resulting stopVideo(5) so crossfade doesn't fire on
            // a mode switch for the same song.
            inVideoMode = false;
            skipNextCrossfade = true;
            Log.d(TAG, "videoToggle → ALLOW (video→audio), inVideoMode=false, skipNext=true");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Could not check video toggle state", e);
            return false;
        }
    }

    private static void showVideoBlockedToast() {
        try {
            Context ctx = getAppContext();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx,
                    "Video mode is not available while crossfade is enabled",
                    Toast.LENGTH_SHORT).show()
            );
        } catch (Exception ignored) {}
    }

    private static boolean isEnabled() {
        return Settings.CROSSFADE_ENABLED.get();
    }

    private static boolean isSessionControlEnabled() {
        return Settings.CROSSFADE_SESSION_CONTROL.get();
    }

    private static int getCrossfadeDurationMs() {
        if (Settings.CROSSFADE_ADVANCED_MODE.get()) {
            int ms = Settings.CROSSFADE_DURATION_MS.get();
            return Math.max(500, Math.min(30000, ms));
        }
        int sec = Settings.CROSSFADE_DURATION.get();
        return Math.max(1, Math.min(12, sec)) * 1000;
    }

    private static long getLongPressThresholdMs() {
        int ms = Settings.CROSSFADE_LONG_PRESS_DURATION.get();
        return Math.max(300, Math.min(2000, ms));
    }

    // ------------------------------------------------------------------ //
    //  Long-press shuffle button to toggle crossfade session               //
    // ------------------------------------------------------------------ //

    private static final String[] SHUFFLE_IDS = {
        "queue_shuffle_button", "queue_shuffle",
        "playback_queue_shuffle_button_view",
        "overlay_queue_shuffle_button_view"
    };

    private static Runnable pendingLongPress;
    private static volatile boolean longPressHandled = false;

    /**
     * Finds ALL shuffle button instances in the view hierarchy (main player,
     * mini player, overlay, etc.) and attaches a touch-based long-press
     * handler to each one.  Uses OnTouchListener with a timer rather than
     * OnLongClickListener to avoid interference from custom touch handling.
     */
    private static void tryAttachLongPressHandler() {
        if (!isSessionControlEnabled() || !isEnabled()) return;

        boolean allAlive = !longPressRefs.isEmpty();
        for (WeakReference<View> ref : longPressRefs) {
            View v = ref.get();
            if (v == null || !v.isAttachedToWindow()) {
                allAlive = false;
                break;
            }
        }
        if (allAlive && !longPressRefs.isEmpty()) return;

        mainHandler.post(() -> {
            try {
                Activity activity = getTopActivity();
                if (activity == null || activity.getWindow() == null) return;

                View decorView = activity.getWindow().getDecorView();
                Resources res = activity.getResources();
                String pkg = activity.getPackageName();

                java.util.List<View> allButtons = new java.util.ArrayList<>();
                for (String idName : SHUFFLE_IDS) {
                    int id = res.getIdentifier(idName, "id", pkg);
                    if (id == 0) continue;
                    findAllViewsById(decorView, id, allButtons);
                }

                Log.d(TAG, "Found " + allButtons.size()
                        + " shuffle button instances");

                longPressRefs.clear();

                for (View shuffleBtn : allButtons) {
                    attachTouchLongPress(shuffleBtn);
                    longPressRefs.add(new WeakReference<>(shuffleBtn));

                    View parent = (View) shuffleBtn.getParent();
                    if (parent != null && parent != decorView) {
                        attachTouchLongPress(parent);
                        longPressRefs.add(new WeakReference<>(parent));
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Long-press attach skipped: " + e.getMessage());
            }
        });
    }

    private static void findAllViewsById(View root, int id,
                                          java.util.List<View> out) {
        if (root.getId() == id) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAllViewsById(vg.getChildAt(i), id, out);
            }
        }
    }

    private static void attachTouchLongPress(View btn) {
        final float[] downXY = new float[2];
        final boolean[] longPressTriggered = {false};

        btn.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downXY[0] = event.getRawX();
                    downXY[1] = event.getRawY();
                    longPressTriggered[0] = false;
                    longPressHandled = false;
                    if (pendingLongPress != null) {
                        mainHandler.removeCallbacks(pendingLongPress);
                    }
                    pendingLongPress = () -> {
                        if (longPressHandled) return;
                        longPressHandled = true;
                        longPressTriggered[0] = true;
                        toggleSessionPause();
                        Log.d(TAG, "Shuffle long-press fired ("
                                + getLongPressThresholdMs() + "ms)");
                    };
                    mainHandler.postDelayed(pendingLongPress,
                            getLongPressThresholdMs());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downXY[0];
                    float dy = event.getRawY() - downXY[1];
                    if (Math.sqrt(dx * dx + dy * dy) > 30) {
                        if (pendingLongPress != null) {
                            mainHandler.removeCallbacks(pendingLongPress);
                            pendingLongPress = null;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (pendingLongPress != null) {
                        mainHandler.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                    }
                    if (longPressTriggered[0]) {
                        return true;
                    }
                    v.performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (pendingLongPress != null) {
                        mainHandler.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                    }
                    return true;
            }
            return false;
        });
    }

    private static Activity getTopActivity() {
        try {
            Object activityThread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            if (activityThread == null) return null;
            Field activitiesField = activityThread.getClass().getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activities = activitiesField.get(activityThread);
            if (activities instanceof java.util.Map) {
                for (Object record : ((java.util.Map<?, ?>) activities).values()) {
                    Field pausedField = record.getClass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) {
                        Field activityField = record.getClass().getDeclaredField("activity");
                        activityField.setAccessible(true);
                        return (Activity) activityField.get(record);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getTopActivity failed", e);
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Reflection helpers                                                 //
    // ------------------------------------------------------------------ //

    private static Object getFieldValue(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            return f.get(obj);
        } catch (Exception e) {
            Log.e(TAG, "getFieldValue(" + name + ") on " + obj.getClass().getName() + " failed", e);
            return null;
        }
    }

    private static void setFieldValue(Object obj, String name, Object value) {
        try {
            Field f = findField(obj.getClass(), name);
            f.set(obj, value);
        } catch (Exception e) {
            Log.e(TAG, "setFieldValue(" + name + ") on " + obj.getClass().getName() + " failed", e);
        }
    }

    private static Object tryGetField(Object obj, String name) {
        Class<?> cur = obj.getClass();
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + clazz.getName());
    }

    private static Method findMethod(Object obj, String name, Class<?>... params)
            throws NoSuchMethodException {
        try {
            Method m = obj.getClass().getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {}
        Class<?> cur = obj.getClass();
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name + " on " + obj.getClass().getName());
    }

    private static int callIntMethod(Object obj, String name) throws Exception {
        return (int) findMethod(obj, name).invoke(obj);
    }

    private static long callLongMethod(Object obj, String name) throws Exception {
        return (long) findMethod(obj, name).invoke(obj);
    }

    private static void safeRelease(Object player) {
        try {
            findMethod(player, "P").invoke(player);
        } catch (Exception e) {
            Log.w(TAG, "Partial release (shared resources preserved): "
                    + e.getCause().getMessage());
        }
    }

    private static Context getAppContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Exception e) {
            return null;
        }
    }
}
