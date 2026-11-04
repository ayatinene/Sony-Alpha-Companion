package io.github.ayatinene.sonycompanion.ui

import io.github.ayatinene.sonycompanion.data.RemoteFunction

/**
 * True while the same-connection gallery is switching back to Remote Shooting.
 *
 * The direction is read from [switchTarget] (the function being switched *to*), never from the camera's
 * current function: a camera that already reports Contents Transfer — a retry after a failed browse, or
 * a body left in that mode across a restart — would otherwise be mistaken for an in-progress return.
 * Keeping this decision pure (no Compose, no Android) lets it be unit tested directly on the JVM.
 */
internal fun isReturningToCapture(remote: Boolean, switching: Boolean, switchTarget: String): Boolean =
    remote && switching && switchTarget.equals(RemoteFunction.REMOTE_SHOOTING, ignoreCase = true)
