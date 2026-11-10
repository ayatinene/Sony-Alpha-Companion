package io.github.ayatinene.sonycompanion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.ayatinene.sonycompanion.CompanionApplication

class CompanionViewModel(app:Application): AndroidViewModel(app) {
    val controller=(app as CompanionApplication).controller
}
