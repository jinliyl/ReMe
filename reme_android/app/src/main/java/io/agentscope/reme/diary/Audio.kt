package io.agentscope.reme.diary

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

object Wav {
    fun header(bytes: Int): ByteArray =
        ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(bytes + 36)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(16000)
                putInt(32000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(bytes)
            }
            .array()

    fun repair(file: File): Boolean {
        if (file.length() < 46) return false
        RandomAccessFile(file, "rw").use {
            val bytes = ((it.length() - 44) / 2 * 2).toInt()
            it.write(header(bytes))
            it.setLength(44L + bytes)
        }
        return true
    }
}

data class RecordingState(
    val active: Boolean = false,
    val seconds: Int = 0,
    val level: Float = 0f,
    val error: String = "",
)

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopping = false
    @Volatile private var discard = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> {
                stopping = true
                if (job == null) stopSelf()
            }
            "cancel" -> {
                discard = true
                stopping = true
            }
            else ->
                if (job == null) {
                    if (
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED || repository().connection() == null
                    ) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(
                        NotificationChannel("recording", "正在记录", NotificationManager.IMPORTANCE_LOW)
                    )
                    val stop =
                        PendingIntent.getService(
                            this,
                            1,
                            Intent(this, RecordingService::class.java).setAction("stop"),
                            PendingIntent.FLAG_IMMUTABLE,
                        )
                    startForeground(
                        11,
                        NotificationCompat.Builder(this, "recording")
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("正在记录你的想法")
                            .setContentText("点击完成保存录音，最长两分钟")
                            .addAction(0, "完成", stop)
                            .setOngoing(true)
                            .build(),
                    )
                    job = scope.launch {
                        record(
                            intent?.getStringExtra("capture_id") ?: newId(),
                            intent?.getStringExtra("target_kind") ?: "general",
                            intent?.getStringExtra("target_id"),
                        )
                    }
                }
        }
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun record(id: String, targetKind: String, targetId: String?) {
        val server = repository().connection() ?: return
        val folder = File(filesDir, "recordings").apply { mkdirs() }
        val audio = File(folder, "$id.wav")
        val metadata = File(folder, "$id.json")
        val info =
            JSONObject()
                .put("scope", server.scope)
                .put("id", id)
                .put("captured_at", ZonedDateTime.now().toOffsetDateTime().toString())
                .put("target_kind", targetKind)
        targetId?.let { info.put("target_id", it) }
        metadata.writeText(info.toString())
        var recorder: AudioRecord? = null
        var failed = false
        try {
            val minimum =
                AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            recorder =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minimum.coerceAtLeast(6400),
                )
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            RandomAccessFile(audio, "rw").use { output ->
                output.write(Wav.header(0))
                recorder.startRecording()
                val buffer = ShortArray(1600)
                var count = 0
                while (!stopping && count < 16000 * 120 && currentCoroutineContext().isActive) {
                    val size = recorder.read(buffer, 0, buffer.size)
                    check(size > 0)
                    val data = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var peak = 0
                    for (index in 0 until size) {
                        data.putShort(buffer[index])
                        peak = maxOf(peak, abs(buffer[index].toInt()))
                    }
                    output.write(data.array())
                    count += size
                    if (count % 16000 < size) {
                        val end = output.filePointer
                        output.seek(0)
                        output.write(Wav.header(count * 2))
                        output.seek(end)
                        output.fd.sync()
                    }
                    state.value = RecordingState(true, count / 16000, peak / 32768f)
                }
                output.seek(0)
                output.write(Wav.header(count * 2))
                output.fd.sync()
            }
        } catch (error: Exception) {
            failed = true
            state.value = RecordingState(error = "录音中断，已保留可恢复的录音")
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            withContext(NonCancellable) {
                try {
                    if (discard) {
                        audio.delete()
                        metadata.delete()
                    } else if (Wav.repair(audio))
                        saveRecording(this@RecordingService, repository(), audio, metadata, info)
                    if (!failed) state.value = RecordingState()
                } catch (_: Exception) {
                    state.value = RecordingState(error = "录音原件已保留，重新打开应用后会恢复保存")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        super.onDestroy()
    }

    companion object {
        val state = MutableStateFlow(RecordingState())

        private suspend fun saveRecording(
            context: Context,
            repo: DiaryRepository,
            file: File,
            metadata: File,
            info: JSONObject,
        ) {
            repo.capture(
                null,
                file,
                info.getString("scope"),
                info.getString("id"),
                info.getString("captured_at"),
                info.str("target_kind", "general"),
                info.str("target_id").ifEmpty { null },
            )
            metadata.delete()
        }

        suspend fun recover(context: Context, repo: DiaryRepository) {
            File(context.filesDir, "recordings")
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.forEach { metadata ->
                    runCatching {
                        val audio =
                            File(metadata.parentFile, metadata.nameWithoutExtension + ".wav")
                        if (Wav.repair(audio))
                            saveRecording(
                                context,
                                repo,
                                audio,
                                metadata,
                                JSONObject(metadata.readText()),
                            )
                    }
                }
        }
    }
}

class VoicePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var audioFile: File? = null
    val playing = MutableStateFlow(false)

    fun stop() {
        player?.release()
        player = null
        playing.value = false
        focus?.let {
            context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
        }
        focus = null
        audioFile?.delete()
        audioFile = null
    }

    suspend fun speak(api: DiaryApi, run: JSONObject, voice: String = "") {
        stop()
        val response =
            api.post(
                "android_tts",
                JSONObject()
                    .put("run_id", run.getString("id"))
                    .put("message_id", run.getString("message_id"))
                    .apply { if (voice.isNotEmpty()) put("voice", voice) },
            )
        play(
            android.util.Base64.decode(response.getString("audio"), android.util.Base64.NO_WRAP),
            response.str("format", "mp3"),
        )
    }

    suspend fun original(api: DiaryApi, id: String) {
        stop()
        val response = api.post("android_read", JSONObject().put("kind", "resource").put("id", id))
        play(
            android.util.Base64.decode(response.getString("audio"), android.util.Base64.NO_WRAP),
            "wav",
        )
    }

    private suspend fun play(bytes: ByteArray, format: String) {
        val file =
            withContext(Dispatchers.IO) {
                File.createTempFile(
                        "reply-",
                        ".${format.filter { it.isLetterOrDigit() }}",
                        context.cacheDir,
                    )
                    .apply { writeBytes(bytes) }
            }
        audioFile = file
        val manager = context.getSystemService(AudioManager::class.java)
        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
                .build()
        focus = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop()
            return
        }
        player =
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    it.start()
                    playing.value = true
                }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                prepareAsync()
            }
    }
}
