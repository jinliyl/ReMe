package io.agentscope.reme.diary

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Opt-in test against the local real Python service. No model keys are installed in Android. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class LocalIntegrationTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun aRealServiceDiaryTaskAndNativeNotification() {
        val input = File(context.filesDir, "integration-connection.json")
        assumeTrue(
            "Provide the private local connection file to run this integration",
            input.isFile,
        )
        val config = JSONObject(input.readText())
        val expectedInstance = runBlocking {
            DiaryApi(Connection(config.getString("origin"), config.getString("token"), ""))
                .post("android_connect", JSONObject().put("device_id", "emulator_test"))
                .getString("instance_id")
        }
        if (context.repository().connection() != null)
            ui.onNodeWithContentDescription("连接设置").performClick()
        ui.onNodeWithTag("server_address").performTextReplacement("http://127.0.0.1")
        ui.onNodeWithTag("server_port")
            .performTextReplacement(config.getString("origin").toHttpUrl().port.toString())
        ui.onNodeWithTag("server_token").performTextReplacement(config.getString("token"))
        ui.onNodeWithText("保存并连接").performClick()
        ui.waitUntil(30000) { context.repository().connection()?.instance == expectedInstance }
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        screenshot("android-home")
        val repo = context.repository()
        val server = repo.connection()!!
        val previous = runBlocking {
            repo.dao.records(server.scope).filter { it.kind == "run" }.map { it.id }.toSet()
        }
        val sentence = "我想做一个语音日记本。明天上午九点提醒我比较安卓录音方案。"
        ui.onNodeWithTag("composer_input").performTextInput(sentence)
        ui.onNodeWithContentDescription("发送记录").performClick()
        var processed: JSONObject? = null
        ui.waitUntil(360000) {
            runBlocking {
                repo.dao
                    .records(server.scope)
                    .firstOrNull {
                        it.kind == "run" &&
                            it.id !in previous &&
                            it.value().str("text") == sentence &&
                            it.value().str("status") == "completed"
                    }
                    ?.also { processed = it.value() } != null
            }
        }
        // SSE publishes the run before the next document sync reaches Room.
        ui.waitUntil(60000) {
            runBlocking {
                val items = repo.dao.records(server.scope)
                val changed = processed!!.getJSONArray("documents")
                // Repeating an existing reminder may update only the idea and journal.
                (0 until changed.length()).all { i ->
                    items.any { it.id == changed.getString(i) }
                } &&
                    items.any { it.kind == "task" && it.value().str("status") == "open" } &&
                    items.any { it.kind == "idea" }
            }
        }
        val records = runBlocking { repo.dao.records(server.scope) }
        assertTrue(records.any { it.kind == "task" })
        assertTrue(records.any { it.kind == "idea" })
        screenshot("android-conversation")
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("nav_tasks").performClick()
        screenshot("android-tasks")
        val task =
            records
                .first {
                    it.kind == "task" &&
                        it.value().str("status") == "open" &&
                        it.value().str("title").contains("录音") &&
                        it.value().str("remind_at").isNotEmpty()
                }
                .value()
        runBlocking {
            repo.update(
                task,
                "reschedule",
                JSONObject().put("remind_at", OffsetDateTime.now().plusSeconds(20).toString()),
            )
            repo.synchronize()
        }
        ui.waitUntil(50000) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.activeNotifications.any { it.notification.channelId == "reminders" }
        }
        if (
            runCatching { context.packageManager.getPackageInfo("com.android.systemui", 0) }
                .isSuccess
        ) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("cmd statusbar expand-notifications").close()
            Thread.sleep(1500)
            screenshot("android-notification")
            automation.executeShellCommand("cmd statusbar collapse").close()
        }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val notification =
            manager.activeNotifications.first {
                it.notification.channelId == "reminders"
            }
        notification.notification.actions.first { it.title == "完成" }.actionIntent.send()
        ui.waitUntil(60000) {
            runBlocking {
                repo.dao.find(server.scope, "task", task.getString("id"))?.value()?.str("status") ==
                    "done"
            }
        }
        assertFalse(
            runBlocking {
                repo.dao.records(server.scope).any {
                    it.kind == "reminder" && it.value().str("task_id") == task.getString("id")
                }
            }
        )
        screenshot("android-task-completed")
        File(context.filesDir, "integration-result.json")
            .writeText(
                JSONObject()
                    .put("success", true)
                    .put("real_model", true)
                    .put("native_notification", true)
                    .put("task_completion", true)
                    .toString()
            )
    }

    @Test
    fun bEmulatedMicrophoneRealAsrAndVoiceReply() {
        assumeTrue(File(context.filesDir, "integration-connection.json").isFile)
        val repo = context.repository()
        val connection = repo.connection() ?: error("The connection test must finish first")
        val previous = runBlocking {
            repo.dao.records(connection.scope).filter { it.kind == "run" }.map { it.id }.toSet()
        }
        ui.onNodeWithContentDescription("开始录音").performClick()
        ui.waitUntil(10000) { RecordingService.state.value.active }
        File(context.filesDir, "microphone-ready").writeText(System.currentTimeMillis().toString())
        ui.waitUntil(35000) { RecordingService.state.value.seconds >= 18 }
        screenshot("android-recording")
        ui.onNodeWithText("完成并保存").performClick()
        ui.waitUntil(10000) { !RecordingService.state.value.active }
        var recognized: JSONObject? = null
        ui.waitUntil(360000) {
            recognized = runBlocking {
                repo.dao
                    .records(connection.scope)
                    .firstOrNull {
                        it.kind == "run" &&
                            it.id !in previous &&
                            it.value().str("kind") == "audio" &&
                            it.value().str("status") == "completed"
                    }
                    ?.value()
            }
            recognized != null
        }
        assertTrue(
            "Injected speech must pass through AudioRecord and real ASR",
            recognized!!.str("text").contains("日记"),
        )
        ui.waitUntil(60000) {
            ui.onAllNodesWithContentDescription("停止朗读").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("停止朗读").performClick()
        val reportFile = File(context.filesDir, "integration-result.json")
        val report =
            JSONObject(reportFile.readText())
                .put("emulated_microphone_real_asr", true)
                .put("native_tts_playback", true)
        reportFile.writeText(report.toString())
    }
}
