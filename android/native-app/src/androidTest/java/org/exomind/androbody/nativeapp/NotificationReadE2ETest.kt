package org.exomind.androbody.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.config.AppPreferences
import org.exomind.androbody.nativeapp.config.NativeCapabilityChoice
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.events.NotificationAccess
import org.exomind.androbody.nativeapp.events.sha256
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Runs a synthetic notification through the other-package listener and user/Agent read surfaces. */
class NotificationReadE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun otherPackageNotificationReachesInboxAgentToolAndBodyList(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val emitter = instrumentation.context
        assertTrue(target.packageName.endsWith(".verification"))
        assertNotEquals(target.packageName, emitter.packageName)
        assertFalse("The emulator harness must begin with this listener disabled",
            NotificationAccess.isEnabled(target))

        val marker = UUID.randomUUID().toString().replace("-", "")
        val title = "AMQA$marker"
        val body = "AMQABODY$marker"
        val notificationId = NotificationFixtureReceiver.NOTIFICATION_ID
        val component = "${emitter.packageName}/${NotificationFixtureReceiver::class.java.name}"
        val appGraph = AtomicReference<AppGraph?>()
        val previousPreferences = AtomicReference<AppPreferences?>()
        val preferencesReady = CompletableDeferred<Unit>()

        fun sendFixture(action: String): String {
            val command = buildString {
                append("am broadcast -n ").append(component)
                append(" -a ").append(action)
                append(" --ei ").append(NotificationFixtureReceiver.EXTRA_NOTIFICATION_ID).append(' ')
                    .append(notificationId)
                if (action == NotificationFixtureReceiver.ACTION_POST) {
                    append(" --es ").append(NotificationFixtureReceiver.EXTRA_TITLE).append(' ').append(title)
                    append(" --es ").append(NotificationFixtureReceiver.EXTRA_BODY).append(' ').append(body)
                }
            }
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                it.onActivity { activity ->
                    val graph = activity.applicationContext.graph
                    appGraph.set(graph)
                    previousPreferences.set(graph.preferences.state.value)
                    graph.scope.launch {
                        try {
                            graph.preferences.update { current -> current.copy(
                                nativeCapabilityChoices = current.nativeCapabilityChoices +
                                    ("am_notifications_recent" to NativeCapabilityChoice.ALLOW),
                                triggersEnabled = false,
                                triggerCloudConsent = false,
                            ) }
                            preferencesReady.complete(Unit)
                        } catch (failure: Throwable) {
                            preferencesReady.completeExceptionally(failure)
                        }
                    }
                }
                withTimeout(10_000) { preferencesReady.await() }
                val graph = requireNotNull(appGraph.get())

                compose.onNodeWithTag("nav-BODY").performClick()
                val bodyList = compose.onNodeWithTag("body-list")
                bodyList.performScrollToNode(hasText("接收通知"))
                val listenerComponent =
                    "${target.packageName}/org.exomind.androbody.nativeapp.events.NoticeListener"
                runShell(instrumentation,
                    "cmd notification allow_listener $listenerComponent 0")
                withTimeout(10_000) {
                    while (!NotificationAccess.isEnabled(target)) delay(100)
                }
                compose.onNodeWithTag("notification-refresh").performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("系统授权：已开启 · 监听服务：已连接", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }

                val observedBefore = graph.networkDiagnostics.events.value.count {
                    it.subsystem == "notifications" && it.stage == "notification_posted_observed"
                }
                val postResult = sendFixture(NotificationFixtureReceiver.ACTION_POST)
                assertTrue(postResult.contains("Broadcast completed"))
                assertTrue("AndroidTest notifications are disabled: $postResult",
                    postResult.contains("notificationsEnabled=true"))
                assertTrue("Synthetic notification was not requested: $postResult",
                    postResult.contains("postRequested=true"))
                val sourcePackageHash = sha256(emitter.packageName).take(16)
                compose.waitUntil(10_000) {
                    graph.networkDiagnostics.events.value.count {
                        it.subsystem == "notifications" && it.stage == "notification_posted_observed" &&
                            it.fields["sourcePackageHash"] == sourcePackageHash
                    } > observedBefore
                }
                compose.waitUntil(10_000) {
                    graph.networkDiagnostics.events.value.any {
                        it.subsystem == "notifications" && it.stage == "notification_inbox_persisted" &&
                            it.fields["sourcePackageHash"] == sourcePackageHash
                    }
                }

                val resultDeferred = CompletableDeferred<Receipt>()
                graph.scope.launch {
                    try {
                        resultDeferred.complete(graph.broker.invoke(Invocation(
                            requestId = "notification-read-$marker",
                            callId = "notification-read-fixture",
                            principal = Principal("native:user", Origin.NATIVE),
                            capability = "am_notifications_recent",
                            arguments = JSONObject().put("limit", 50).put("activeOnly", true),
                        )))
                    } catch (failure: Throwable) {
                        resultDeferred.completeExceptionally(failure)
                    }
                }
                val result = withTimeout(10_000) { resultDeferred.await() }
                assertEquals(Outcome.COMPLETED, result.outcome)
                val notifications = result.data.getJSONArray("notifications").objects()
                val row = notifications
                    .firstOrNull { it.optString("title") == title }
                assertNotNull("Expected the active synthetic notification; returned app count=${notifications.size}", row)
                assertEquals(emitter.packageName, row!!.getString("packageName"))
                assertEquals(body, row.getString("body"))
                assertEquals("untrusted_notification_data", row.getString("contentTrust"))
                assertTrue(row.getBoolean("active"))

                compose.waitUntil(10_000) {
                    graph.notificationInbox.items.value.any { it.title == title && it.active }
                }
                bodyList.performScrollToNode(hasText(title))
                compose.onNodeWithText(title, substring = false).performClick()
                compose.onNodeWithTag("notification-detail-dialog").assertExists()
                compose.onAllNodesWithText(body, substring = false).assertCountEquals(2)
                compose.onNodeWithText(emitter.packageName, substring = false).assertExists()
            }
        } finally {
            runCatching { sendFixture(NotificationFixtureReceiver.ACTION_CANCEL) }
            runCatching {
                val listenerComponent =
                    "${target.packageName}/org.exomind.androbody.nativeapp.events.NoticeListener"
                runShell(instrumentation,
                    "cmd notification disallow_listener $listenerComponent 0")
            }
            withTimeout(10_000) {
                while (NotificationAccess.isEnabled(target)) delay(100)
            }
            val graph = appGraph.get()
            val previous = previousPreferences.get()
            if (graph != null && previous != null) graph.preferences.update { it.copy(
                nativeApprovalMode = previous.nativeApprovalMode,
                nativeCapabilityChoices = previous.nativeCapabilityChoices,
                triggersEnabled = previous.triggersEnabled,
                triggerCloudConsent = previous.triggerCloudConsent,
                triggerPackages = previous.triggerPackages,
                triggerKeyword = previous.triggerKeyword,
            ) }
        }
    }

    private fun runShell(instrumentation: android.app.Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
    }
}
