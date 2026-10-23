package io.github.ayatinene.sonycompanion

import android.app.Application
import io.github.ayatinene.sonycompanion.data.CompanionController

class CompanionApplication: Application() {
    val controller by lazy { CompanionController(this) }
}
