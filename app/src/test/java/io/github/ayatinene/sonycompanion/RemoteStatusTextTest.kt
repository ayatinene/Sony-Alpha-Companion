package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.ui.remoteStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteStatusTextTest {
    @Test fun idleCameraReadsAsStandby() {
        assertEquals("待机", remoteStatusLabel("IDLE"))
        assertEquals("待机", remoteStatusLabel("idle"))
    }

    @Test fun recordingCameraReadsAsRecording() {
        assertEquals("录像中", remoteStatusLabel("MovieRecording"))
        assertEquals("录像中", remoteStatusLabel("movieRecording"))
    }

    @Test fun unknownOrMissingStatusNeverPretendsToBeIdle() {
        assertEquals("已连接", remoteStatusLabel(""))
        assertEquals("已连接", remoteStatusLabel("StillCapturing"))
        assertEquals("已连接", remoteStatusLabel("SomeFutureState"))
    }
}
