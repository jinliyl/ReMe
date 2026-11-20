package io.agentscope.reme.diary

import java.io.File
import java.time.ZonedDateTime
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in JVM check of the exact HTTP client used in the APK, against real providers. */
class RealClientTest {
    @Test
    fun actualAndroidClientUsesAuthenticatedVoicePipeline() = runBlocking {
        val path = System.getenv("REME_LOCAL_CONNECTION")
        val audio = System.getenv("REME_LOCAL_AUDIO")
        assumeTrue(path != null && audio != null)
        val settings = JSONObject(File(path!!).readText())
        val connection = Connection(settings.getString("origin"), settings.getString("token"), "")
        val api = DiaryApi(connection)
        val identity = api.post("android_connect", JSONObject().put("device_id", "native_jvm_test"))
        assertEquals(1, identity.getInt("protocol_version"))
        try {
            DiaryApi(connection.copy(token = "wrong"))
                .post("android_connect", JSONObject().put("device_id", "native_jvm_test"))
            fail("An incorrect token must fail before model invocation")
        } catch (failure: ApiFailure) {
            assertEquals(401, failure.code)
        }
        val id = java.util.UUID.randomUUID().toString()
        val input =
            JSONObject()
                .put("capture_id", id)
                .put("kind", "audio")
                .put("timezone", "Asia/Shanghai")
                .put(
                    "captured_at",
                    ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .toOffsetDateTime()
                        .toString(),
                )
                .put("audio", Base64.getEncoder().encodeToString(File(audio!!).readBytes()))
        assertTrue(api.post("android_capture", input).getBoolean("saved"))
        assertEquals(id, api.post("android_capture", input).getString("run_id"))
        val deadline = System.currentTimeMillis() + 360000
        var run: JSONObject
        do {
            run = api.post("android_read", JSONObject().put("kind", "run").put("id", id))
            if (run.str("status") in listOf("completed", "failed", "cancelled")) break
            delay(500)
        } while (System.currentTimeMillis() < deadline)
        assertEquals("completed", run.str("status"))
        assertTrue(run.str("text").contains("日记"))
        assertTrue(run.str("answer").isNotBlank())
        val reply =
            api.post(
                "android_tts",
                JSONObject().put("run_id", id).put("message_id", run.getString("message_id")),
            )
        assertTrue(reply.getBoolean("is_last"))
        assertTrue(Base64.getDecoder().decode(reply.getString("audio")).size > 100)
    }
}
