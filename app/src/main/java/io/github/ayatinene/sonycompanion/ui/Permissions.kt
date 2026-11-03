package io.github.ayatinene.sonycompanion.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.ayatinene.sonycompanion.data.CameraMode

internal const val PERMISSION_REQUIRED_DENIED = "未授予所需权限，无法连接相机。"
internal const val PERMISSION_PERMANENT_DENIED = "所需权限已被拒绝，请在系统设置中开启后重试。"
internal const val PERMISSION_NOTIFICATION_DENIED = "未授予通知权限，后台扫描或下载时可能没有进度提醒；连接与拍照不受影响。"

/**
 * No runtime permission is required to connect: discovery uses ConnectivityManager + SSDP + a
 * WifiManager multicast lock, not Wi-Fi scan APIs. Notifications only power the background notice.
 */
internal object ConnectionPermissionPolicy {
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    fun required(sdk: Int): List<String> = emptyList()

    fun optional(sdk: Int): List<String> = if (sdk >= 33) listOf(NOTIFICATIONS) else emptyList()
}

internal interface ConnectionPermissions {
    fun required(): List<String>
    fun optional(): List<String>
}

internal object PlatformConnectionPermissions : ConnectionPermissions {
    override fun required(): List<String> = ConnectionPermissionPolicy.required(Build.VERSION.SDK_INT)
    override fun optional(): List<String> = ConnectionPermissionPolicy.optional(Build.VERSION.SDK_INT)
}

internal data class PermissionRequestPlan(val request: List<String>, val blockedPermanently: Boolean)

/** Required permissions are re-asked only while a rationale can still be shown; optional ones at most once. */
internal fun planPermissionRequest(
    required: List<String>,
    optional: List<String>,
    granted: (String) -> Boolean,
    asked: Set<String>,
    canAskAgain: (String) -> Boolean,
): PermissionRequestPlan {
    val missingRequired = required.filter { !granted(it) }
    val askRequired = missingRequired.filter { it !in asked || canAskAgain(it) }
    if (missingRequired.isNotEmpty() && askRequired.isEmpty()) return PermissionRequestPlan(emptyList(), blockedPermanently = true)
    val askOptional = optional.filter { !granted(it) && it !in asked }
    return PermissionRequestPlan(askRequired + askOptional, blockedPermanently = false)
}

internal enum class ConnectionPermissionOutcome { CONNECT, DENIED, PERMANENTLY_DENIED }

internal fun connectionPermissionOutcome(required: List<String>, granted: Map<String, Boolean>, canAskAgain: Boolean): ConnectionPermissionOutcome =
    when {
        required.all { granted[it] == true } -> ConnectionPermissionOutcome.CONNECT
        canAskAgain -> ConnectionPermissionOutcome.DENIED
        else -> ConnectionPermissionOutcome.PERMANENTLY_DENIED
    }

internal fun interface PermissionLauncher {
    fun launch(permissions: List<String>)
}

/** One composition-level result callback, so a result re-delivered after recreation is handled by the current composition. */
@Composable
internal fun rememberPermissionLauncher(onResult: (Map<String, Boolean>) -> Unit): PermissionLauncher {
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results -> latest.value(results) }
    return remember(launcher) { PermissionLauncher { permissions -> launcher.launch(permissions.toTypedArray()) } }
}

internal fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}

/** Persists which runtime permissions were already asked, so a denial is not re-prompted after a restart. */
internal interface PermissionAskStore {
    fun asked(): Set<String>
    fun markAsked(permissions: Collection<String>)
}

internal object NoPermissionAskStore : PermissionAskStore {
    override fun asked(): Set<String> = emptySet()
    override fun markAsked(permissions: Collection<String>) {}
}

internal class PrefsPermissionAskStore(private val prefs: SharedPreferences) : PermissionAskStore {
    override fun asked(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    override fun markAsked(permissions: Collection<String>) {
        val merged = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet().apply { addAll(permissions) }
        prefs.edit().putStringSet(KEY, merged).apply()
    }

    companion object { const val KEY = "askedRuntimePermissions" }
}

internal val permissionSetSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })

/**
 * Gate for every mode card and the reconnect entry: connect directly when nothing is needed, otherwise
 * ask the missing permissions and auto-continue once granted.
 */
@Composable
internal fun rememberConnectionGuard(
    onConnect: (CameraMode) -> Unit,
    onDenied: (String) -> Unit,
    onPermanentDenied: (String) -> Unit,
    onNotificationDenied: (String) -> Unit,
    permissions: ConnectionPermissions = PlatformConnectionPermissions,
    isGranted: (Context, String) -> Boolean = ::isPermissionGranted,
    askStore: PermissionAskStore = NoPermissionAskStore,
    permissionLauncherFactory: @Composable ((Map<String, Boolean>) -> Unit) -> PermissionLauncher = { rememberPermissionLauncher(it) },
): (CameraMode) -> Unit {
    val context = LocalContext.current
    var pendingMode by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable(stateSaver = permissionSetSaver) { mutableStateOf(askStore.asked()) }
    var notifiedOnce by rememberSaveable { mutableStateOf(false) }

    fun canAskAgain(permission: String): Boolean =
        context.findActivityOrNull()?.shouldShowRequestPermissionRationale(permission) == true

    fun resolve(mode: CameraMode, results: Map<String, Boolean>?) {
        val required = permissions.required()
        val optional = permissions.optional()
        // Merge the result with live grants, so a map covering only the requested subset never reads an
        // already-granted permission as denied.
        val granted = (required + optional).associateWith { permission -> results?.get(permission) ?: isGranted(context, permission) }
        when (connectionPermissionOutcome(required, granted, required.any { canAskAgain(it) })) {
            ConnectionPermissionOutcome.CONNECT -> {
                // Only explain a notification denial when it just happened, not on every later connect.
                if (results != null && optional.any { granted[it] != true } && !notifiedOnce) {
                    notifiedOnce = true
                    onNotificationDenied(PERMISSION_NOTIFICATION_DENIED)
                }
                onConnect(mode)
            }
            ConnectionPermissionOutcome.DENIED -> onDenied(PERMISSION_REQUIRED_DENIED)
            ConnectionPermissionOutcome.PERMANENTLY_DENIED -> onPermanentDenied(PERMISSION_PERMANENT_DENIED)
        }
    }

    val launcher = permissionLauncherFactory { results ->
        val name = pendingMode
        if (name.isNotEmpty()) {
            pendingMode = ""
            CameraMode.entries.firstOrNull { it.name == name }?.let { resolve(it, results) }
        }
    }

    return { mode ->
        val plan = planPermissionRequest(permissions.required(), permissions.optional(), { isGranted(context, it) }, asked, ::canAskAgain)
        when {
            plan.blockedPermanently -> onPermanentDenied(PERMISSION_PERMANENT_DENIED)
            plan.request.isEmpty() -> resolve(mode, null)
            else -> {
                asked = asked + plan.request
                askStore.markAsked(plan.request)
                pendingMode = mode.name
                launcher.launch(plan.request)
            }
        }
    }
}
