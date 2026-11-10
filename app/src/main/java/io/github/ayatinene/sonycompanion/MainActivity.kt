package io.github.ayatinene.sonycompanion

import android.Manifest
import android.content.Intent
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import io.github.ayatinene.sonycompanion.ui.*

class MainActivity: ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val model by lazy { ViewModelProvider(this)[CompanionViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CompanionApp(model,openWifi={ startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },requestPermissions={
            if(Build.VERSION.SDK_INT>=33) permission.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES,Manifest.permission.POST_NOTIFICATIONS))
        }) }
    }
    override fun onStart() { super.onStart(); model.controller.setVisible(true) }
    override fun onStop() { model.controller.setVisible(false); super.onStop() }
}
