package org.exomind.androbody.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.exomind.androbody.nativeapp.runtime.NativeSessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeSessionStoreTest {
    @Test fun createRenameSwitchForkArchiveAndDeleteRemainScopedToTheirSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.also {
            check(it.packageName.endsWith(".verification"))
        }
        val store = NativeSessionStore(context)
        val baseline = store.open()
        val first = store.create("First")
        val now = System.currentTimeMillis()
        store.save(first.info.id, JSONObject().put("schema", 3).put("running", false)
            .put("mayHaveDeviceEffects", false).put("history", JSONArray())
            .put("entries", JSONArray().put(JSONObject().put("id", "first-user").put("role", "user")
                .put("text", "first task").put("at", now).put("requestId", "first-request"))))
        val second = store.create("Second")
        store.rename(first.info.id, "Renamed first")
        assertEquals("Second", store.open().info.title)
        val branch = store.fork(first.info.id)
        assertEquals("Renamed first · 分支", branch.info.title)
        assertEquals("first task", branch.entries.single().text)
        store.setArchived(branch.info.id, true)
        assertTrue(store.all().single { it.id == branch.info.id }.archived)
        store.switch(first.info.id)
        assertEquals("Renamed first", store.open().info.title)
        store.delete(second.info.id)
        store.delete(branch.info.id)
        store.delete(first.info.id)
        store.switch(baseline.info.id)
        val remaining = store.all()
        val fixtureReport = JSONObject().put("baselineId", baseline.info.id)
            .put("firstId", first.info.id).put("secondId", second.info.id).put("branchId", branch.info.id)
            .put("remaining", JSONArray(remaining.map { JSONObject().put("id", it.id).put("title", it.title) }))
        File(context.filesDir, "qa/evidence/session-store-roundtrip.json").apply {
            parentFile?.mkdirs(); writeText(fixtureReport.toString(2), Charsets.UTF_8)
        }
        assertTrue("Unexpected retained test sessions: ${remaining.filterNot { it.id == baseline.info.id }.joinToString { "${it.id}:${it.title}" }}",
            remaining.all { it.id == baseline.info.id })
    }
}
