package io.github.ayatinene.sonycompanion.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import io.github.ayatinene.sonycompanion.pairing.PairingCredentials
import androidx.lifecycle.AndroidViewModel
import io.github.ayatinene.sonycompanion.CompanionApplication

class CompanionViewModel(app:Application): AndroidViewModel(app) {
    // Memory only: pending runtime permission results survive rotation, never process death.
    var pairingInput by mutableStateOf<PairingCredentials?>(null)
    var pairingPermissionPending by mutableStateOf(false)
    var pairingNotice by mutableStateOf<String?>(null)
    val controller=(app as CompanionApplication).controller
}

