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

/**
 * The workspace that matches the live session. A remote session normally opens the full-screen capture
 * page; while the same connection is switched into the camera gallery ([remoteGallery]) it opens the
 * gallery instead. Other modes always use the gallery.
 */
fun workspaceFor(mode: CameraMode?, remoteGallery: Boolean = false): Destination =
    if (mode == CameraMode.REMOTE && !remoteGallery) Destination.Capture else Destination.Gallery

/** The workspace tab is offered only for a live session, never while merely connecting. */
fun destinationsFor(connected: Boolean, mode: CameraMode?, remoteGallery: Boolean = false): List<Destination> =
    if (connected) listOf(Destination.Home, workspaceFor(mode, remoteGallery), Destination.Local, Destination.Settings)
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
 * from consuming the entry on the previous session's still-true `connected`. Only a workspace tab
 * follows a successful mode switch; browsing Home, Local or Settings is never yanked away by a switch.
 */
fun ShellNav.observe(connected: Boolean, connecting: Boolean, mode: CameraMode?, remoteGallery: Boolean = false): ShellNav {
    val phase = when {
        connected -> ConnectionPhase.Connected
        connecting -> ConnectionPhase.Connecting
        else -> ConnectionPhase.Idle
    }
    val workspace = workspaceFor(mode, remoteGallery)
    var nav = this
    val target = nav.entryTarget
    if (target != null) {
        when {
            phase == ConnectionPhase.Connected && !connecting -> nav = nav.copy(destination = target, entryTarget = null)
            phase == ConnectionPhase.Idle -> nav = nav.copy(entryTarget = null)
        }
    }
    if (phase == ConnectionPhase.Idle && nav.workspace) nav = nav.copy(destination = Destination.Home)
    if (phase == ConnectionPhase.Connected && nav.workspace && nav.destination != workspace)
        nav = nav.copy(destination = workspace)
    return nav.copy(phase = phase)
}
