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

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SubtitleTrackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("DISABLE_CAPTIONS_OPTION")
)

internal object StoryboardRendererDecoderRecommendedLevelFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("#-1#")
)

// The "pc" string occurs in several methods; mirror RVX's narrowing by requiring
// the implementation to contain exactly one CONST_STRING instruction.
internal object StartVideoInformerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf("pc"),
    custom = { method, _ ->
        method.implementation
            ?.instructions
            ?.count { it.opcode == Opcode.CONST_STRING } == 1
    }
)
