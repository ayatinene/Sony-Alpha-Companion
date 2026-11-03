package io.github.ayatinene.sonycompanion

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.ui.ConnectionPermissions
import io.github.ayatinene.sonycompanion.ui.PermissionLauncher
import io.github.ayatinene.sonycompanion.ui.rememberConnectionGuard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose coverage for the unified connection guard, using synthetic permissions and an injected
 * launcher so no real system dialog is ever opened.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionPermissionGuardTest {
    @get:Rule val compose=createComposeRule()

    private class FixedPermissions(private val required: List<String>, private val optional: List<String>) : ConnectionPermissions {
        override fun required()=required
        override fun optional()=optional
    }

    private val required=FixedPermissions(listOf("test.REQUIRED"), emptyList())
    private val optionalOnly=FixedPermissions(emptyList(), listOf("test.NOTIFICATION"))

    private fun clickConnect() = compose.onNodeWithText("connect").performClick()

    @Test fun alreadyGrantedConnectsWithoutPrompting() {
        var launches=0; var connects=0
        compose.setContent {
            val connect=rememberConnectionGuard(
                onConnect={ connects++ },onDenied={},onPermanentDenied={},onNotificationDenied={},
                permissions=required,isGranted={ _,_ -> true },
                permissionLauncherFactory={ _ -> PermissionLauncher { launches++ } })
            Button(onClick={ connect(CameraMode.REMOTE) }) { Text("connect") }
        }
        clickConnect()
        compose.runOnIdle { assertEquals(1,connects);assertEquals("no prompt when already granted",0,launches) }
    }

    @Test fun grantAutoContinuesTheOriginalAction() {
        var launches=0; var connects=0
        var handler: ((Map<String,Boolean>) -> Unit)?=null
        compose.setContent {
            val connect=rememberConnectionGuard(
                onConnect={ connects++ },onDenied={},onPermanentDenied={},onNotificationDenied={},
                permissions=required,isGranted={ _,_ -> false },
                permissionLauncherFactory={ h -> handler=h;PermissionLauncher { launches++ } })
            Button(onClick={ connect(CameraMode.REMOTE) }) { Text("connect") }
        }
        clickConnect()
        compose.runOnIdle { assertEquals(1,launches);handler?.invoke(mapOf("test.REQUIRED" to true)) }
        compose.runOnIdle { assertEquals(1,connects) }
    }

    @Test fun deniedRequiredDoesNotConnect() {
        var connects=0; var denied=0; var permanent=0
        var handler: ((Map<String,Boolean>) -> Unit)?=null
        compose.setContent {
            val connect=rememberConnectionGuard(
                onConnect={ connects++ },onDenied={ denied++ },onPermanentDenied={ permanent++ },onNotificationDenied={},
                permissions=required,isGranted={ _,_ -> false },
                permissionLauncherFactory={ h -> handler=h;PermissionLauncher { } })
            Button(onClick={ connect(CameraMode.REMOTE) }) { Text("connect") }
        }
        clickConnect()
        compose.runOnIdle { handler?.invoke(mapOf("test.REQUIRED" to false)) }
        compose.runOnIdle { assertEquals(0,connects);assertEquals(1,denied+permanent) }
    }

    @Test fun notificationDeniedConnectsOnceAndIsNotPromptedAgain() {
        var launches=0; var notices=0; var connects=0
        var handler: ((Map<String,Boolean>) -> Unit)?=null
        compose.setContent {
            val connect=rememberConnectionGuard(
                onConnect={ connects++ },onDenied={},onPermanentDenied={},onNotificationDenied={ notices++ },
                permissions=optionalOnly,isGranted={ _,_ -> false },
                permissionLauncherFactory={ h -> handler=h;PermissionLauncher { launches++ } })
            Button(onClick={ connect(CameraMode.REMOTE) }) { Text("connect") }
        }
        clickConnect()
        compose.runOnIdle { assertEquals(1,launches);handler?.invoke(mapOf("test.NOTIFICATION" to false)) }
        compose.runOnIdle { assertEquals(1,connects);assertEquals(1,notices) }
        clickConnect()
        compose.runOnIdle { assertEquals("optional is not asked twice",1,launches);assertEquals(2,connects);assertEquals(1,notices) }
    }

    @Test fun permanentlyDeniedRequiredIsNotPromptedAgain() {
        var launches=0; var permanent=0; var connects=0
        var handler: ((Map<String,Boolean>) -> Unit)?=null
        compose.setContent {
            val connect=rememberConnectionGuard(
                onConnect={ connects++ },onDenied={},onPermanentDenied={ permanent++ },onNotificationDenied={},
                permissions=required,isGranted={ _,_ -> false },
                permissionLauncherFactory={ h -> handler=h;PermissionLauncher { launches++ } })
            Button(onClick={ connect(CameraMode.REMOTE) }) { Text("connect") }
        }
        clickConnect()
        compose.runOnIdle { assertEquals(1,launches);handler?.invoke(mapOf("test.REQUIRED" to false)) }
        compose.runOnIdle { assertEquals(1,permanent);assertEquals(0,connects) }
        clickConnect()
        compose.runOnIdle { assertEquals("no second prompt after a permanent denial",1,launches);assertEquals(2,permanent) }
    }

    @Test fun recreationDeliversTheResultToTheCurrentCompositionOnce() {
        val tester=StateRestorationTester(compose)
        var launches=0; var connects=0
        var handler: ((Map<String,Boolean>) -> Unit)?=null
        tester.setContent {
            val connect=rememberConnectionGuard(
                onConnect={ connects++ },onDenied={},onPermanentDenied={},onNotificationDenied={},
                permissions=required,isGranted={ _,_ -> false },
                permissionLauncherFactory={ h -> handler=h;PermissionLauncher { launches++ } })
            Button(onClick={ connect(CameraMode.REMOTE) }) { Text("connect") }
        }
        clickConnect()
        compose.runOnIdle { assertEquals(1,launches);assertEquals(0,connects) }
        // Recreation (rotation): resume alone must not consume the pending request or fake a denial.
        tester.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("resume must not connect or deny",0,connects) }
        // The system re-delivers the result to the re-registered callback, which reads the restored intent.
        compose.runOnIdle { handler?.invoke(mapOf("test.REQUIRED" to true)) }
        compose.runOnIdle { assertEquals(1,connects) }
    }
}
