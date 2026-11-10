package io.github.ayatinene.sonycompanion

import android.app.Application
import io.github.ayatinene.sonycompanion.data.CompanionController
import io.github.ayatinene.sonycompanion.data.CrashLog

class CompanionApplication: Application() {
    override fun onCreate() { super.onCreate(); CrashLog.install(filesDir) }
    val controller by lazy { CompanionController(this) }
}
