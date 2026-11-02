package io.github.ayatinene.sonycompanion.ui

fun remoteStatusLabel(status: String): String = when {
    status.equals("IDLE", ignoreCase = true) -> "待机"
    status.contains("MovieRecording", ignoreCase = true) -> "录像中"
    else -> "已连接"
}
