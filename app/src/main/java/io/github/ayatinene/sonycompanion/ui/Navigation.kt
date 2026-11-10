package io.github.ayatinene.sonycompanion.ui

import io.github.ayatinene.sonycompanion.data.CameraMode

enum class Destination { Home, Capture, Gallery, Local, Settings }

fun Destination.title(): String = when (this) {
    Destination.Home -> "首页"
    Destination.Capture -> "拍摄"
    Destination.Gallery -> "相机图库"
    Destination.Local -> "本地"
    Destination.Settings -> "设置"
}

enum class ConnectionPhase { Idle, Connecting, Connected }

fun workspaceFor(mode: CameraMode?): Destination =
    if (mode == CameraMode.REMOTE) Destination.Capture else Destination.Gallery

/** The workspace tab is offered only for a live session, never while merely connecting. */
fun destinationsFor(connected: Boolean, mode: CameraMode?): List<Destination> =
    if (connected) listOf(Destination.Home, workspaceFor(mode), Destination.Local, Destination.Settings)
    else listOf(Destination.Home, Destination.Local, Destination.Settings)

/**
 * Saveable shell navigation. [entryTarget] is armed when the user starts a connection and consumed only
 * once that attempt reports a live session, so a reconnect cannot navigate while the old session is still
 * reported connected. It is dropped on failure or when the user switches page.
 */
data class ShellNav(
    val destination: Destination = Destination.Home,
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val entryTarget: Destination? = null
) {
    val workspace: Boolean get() = destination == Destination.Capture || destination == Destination.Gallery
}

fun ShellNav.requestConnection(mode: CameraMode?): ShellNav = copy(entryTarget = workspaceFor(mode))

fun ShellNav.cancelEntry(): ShellNav = copy(entryTarget = null)

/**
 * Folds one state snapshot into the shell navigation. The `!connecting` guard is what keeps a reconnect
 * from consuming the entry on the previous session's still-true `connected`.
 */
fun ShellNav.observe(connected: Boolean, connecting: Boolean, mode: CameraMode?): ShellNav {
    val phase = when {
        connected -> ConnectionPhase.Connected
        connecting -> ConnectionPhase.Connecting
        else -> ConnectionPhase.Idle
    }
    var nav = this
    val target = nav.entryTarget
    if (target != null) {
        when {
            phase == ConnectionPhase.Connected && !connecting -> nav = nav.copy(destination = target, entryTarget = null)
            phase == ConnectionPhase.Idle -> nav = nav.copy(entryTarget = null)
        }
    }
    if (phase == ConnectionPhase.Idle && nav.workspace) nav = nav.copy(destination = Destination.Home)
    if (phase == ConnectionPhase.Connected && nav.workspace && nav.destination != workspaceFor(mode))
        nav = nav.copy(destination = workspaceFor(mode))
    return nav.copy(phase = phase)
}
