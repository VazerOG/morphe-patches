/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.imageurlhook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object OnFailureFingerprint : Fingerprint(
    classFingerprint = OnResponseStartedFingerprint,
    name = "onFailed",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lorg/chromium/net/UrlRequest;",
        "Lorg/chromium/net/UrlResponseInfo;",
        "Lorg/chromium/net/CronetException;"
    )
)

private object OnResponseStartedFingerprint : Fingerprint(
    name = "onResponseStarted",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;"),
    strings = listOf(
        "Content-Length",
        "Content-Type",
        "identity",
        "application/x-protobuf",
    )
)

internal object OnSucceededFingerprint : Fingerprint(
    classFingerprint = OnResponseStartedFingerprint,
    name = "onSucceeded",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;")
)

internal const val CRONET_URL_REQUEST_CLASS = "Lorg/chromium/net/impl/CronetUrlRequest;"

internal object RequestFingerprint : Fingerprint(
    definingClass = CRONET_URL_REQUEST_CLASS,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
)

private object MessageDigestImageURLParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            string("@#&=*+-_.,:!?()/~'%;$"),
            string("@#&=*+-_.,:!?()/~'%;$[]"),
        )
    )
)

internal object MessageDigestImageURLFingerprint : Fingerprint(
    classFingerprint = MessageDigestImageURLParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    // YTM 9.x's MessageDigestImageUrl class exposes only single-arg constructors
    // (`(Ljava/lang/String;)V` and `(Ljava/net/URL;)V`) — unlike the YouTube
    // app, which has a two-arg `(Ljava/lang/String;, L)V` form. Match the
    // String-first one.
    parameters = listOf("Ljava/lang/String;")
)
