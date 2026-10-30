package org.exomind.androbody.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.exomind.androbody.nativeapp.config.NativeCapabilityChoice
import org.exomind.androbody.nativeapp.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Exercises both Agent tool contracts and the direct Body-page create/edit/delete flow. */
class WorkspaceCrudE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".verification"))
    }

    @Test fun agentToolsCreateReadUpdateGuardStaleDeleteAndDelete() = runBlocking {
        val graph = targetContext().graph
        val preferencesBefore = graph.preferences.state.value
        val path = "qa/agent-crud-${UUID.randomUUID()}.md"
        val principal = Principal("native:user", Origin.NATIVE)
        var callIndex = 0
        suspend fun invoke(capability: String, arguments: JSONObject) = graph.broker.invoke(Invocation(
            requestId = "workspace-crud-${path.hashCode()}",
            callId = "workspace-crud-${++callIndex}",
            principal = principal,
            capability = capability,
            arguments = arguments,
        ))
        graph.preferences.update { current -> current.copy(nativeCapabilityChoices = current.nativeCapabilityChoices + mapOf(
            "am_workspace_write" to NativeCapabilityChoice.ALLOW,
            "am_workspace_delete" to NativeCapabilityChoice.ALLOW,
        )) }

        try {
            val initial = invoke("am_workspace_write", JSONObject().put("path", path).put("text", "agent-created-v1"))
            assertEquals(Outcome.COMPLETED, initial.outcome)
            val initialSha = initial.data.getString("sha256")

            val firstRead = invoke("am_workspace_read", JSONObject().put("path", path))
            assertEquals(Outcome.COMPLETED, firstRead.outcome)
            assertEquals("agent-created-v1", firstRead.data.getString("text"))
            assertEquals(initialSha, firstRead.data.getString("sha256"))

            val update = invoke("am_workspace_write", JSONObject().put("path", path)
                .put("text", "agent-updated-v2").put("expectedSha256", initialSha))
            assertEquals(Outcome.COMPLETED, update.outcome)
            val updatedSha = update.data.getString("sha256")
            assertNotEquals(initialSha, updatedSha)

            val staleDelete = invoke("am_workspace_delete", JSONObject().put("path", path)
                .put("expectedSha256", initialSha))
            assertEquals(Outcome.FAILED, staleDelete.outcome)
            assertEquals("agent-updated-v2", graph.workspace.read(path).getString("text"))

            val delete = invoke("am_workspace_delete", JSONObject().put("path", path)
                .put("expectedSha256", updatedSha))
            assertEquals(Outcome.COMPLETED, delete.outcome)
            assertTrue(delete.data.getBoolean("deleted"))
            val listed = invoke("am_workspace_list", JSONObject())
            assertEquals(Outcome.COMPLETED, listed.outcome)
            assertFalse(listed.data.getJSONArray("files").objects().any { it.optString("path") == path })
        } finally {
            graph.preferences.update { it.copy(
                nativeApprovalMode = preferencesBefore.nativeApprovalMode,
                nativeCapabilityChoices = preferencesBefore.nativeCapabilityChoices,
            ) }
            runCatching {
                val current = graph.workspace.read(path)
                graph.workspace.delete(path, current.getString("sha256"))
            }
        }
    }

    @Test fun bodyPageCreatesEditsReadsBackAndDeletesText() = runBlocking {
        val context = targetContext()
        val graph = context.graph
        val path = "qa/body-crud-${UUID.randomUUID()}.md"
        val firstText = "body-created-v1-${UUID.randomUUID()}"
        val updatedText = "body-updated-v2-${UUID.randomUUID()}"

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-BODY").performClick()
            val bodyList = compose.onNodeWithTag("body-list")
            bodyList.performScrollToNode(hasText("工作区文件"))
            compose.onNodeWithTag("workspace-create").performClick()
            compose.onNodeWithTag("workspace-editor-path").performTextClearance()
            compose.onNodeWithTag("workspace-editor-path").performTextInput(path)
            compose.onNodeWithTag("workspace-editor").performTextInput(firstText)
            compose.onNodeWithTag("workspace-save").performClick()

            compose.waitUntil(10_000) {
                runBlocking { runCatching { graph.workspace.read(path).optString("text") == firstText }.getOrDefault(false) }
            }
            assertEquals(firstText, graph.workspace.read(path).getString("text"))

            compose.onNodeWithTag("workspace-preview-edit").performClick()
            compose.onNodeWithTag("workspace-editor").performTextClearance()
            compose.onNodeWithTag("workspace-editor").performTextInput(updatedText)
            compose.onNodeWithTag("workspace-save").performClick()
            compose.waitUntil(10_000) {
                runBlocking { runCatching { graph.workspace.read(path).optString("text") == updatedText }.getOrDefault(false) }
            }
            assertEquals(updatedText, graph.workspace.read(path).getString("text"))

            compose.onNodeWithTag("workspace-preview-delete", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("workspace-confirm-delete", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) {
                runBlocking {
                    runCatching { graph.workspace.read(path); false }
                        .getOrElse { (it as? BodyFailure)?.code == "NOT_FOUND" }
                }
            }
            compose.onNodeWithText(updatedText, substring = false).assertDoesNotExist()
            bodyList.performScrollToNode(hasText("工作区文件"))
            assertFalse(graph.workspace.list().getJSONArray("files").objects().any { it.optString("path") == path })
        }
    }
}
