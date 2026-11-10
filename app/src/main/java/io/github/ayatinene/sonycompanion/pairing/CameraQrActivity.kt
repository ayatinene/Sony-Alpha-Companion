package io.github.ayatinene.sonycompanion.pairing

import com.journeyapps.barcodescanner.CaptureActivity

/** The embedded decoder owns the camera only while its Activity is resumed. No images are saved. */
class CameraQrActivity : CaptureActivity()
