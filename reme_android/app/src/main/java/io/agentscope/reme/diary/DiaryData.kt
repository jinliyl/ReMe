package io.agentscope.reme.diary

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room.*
import androidx.work.*
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

fun newId(): String = UUID.randomUUID().toString()

fun JSONObject.str(key: String, default: String = ""): String =
    if (isNull(key)) default else optString(key, default)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

@Entity(tableName = "records", primaryKeys = ["scope", "kind", "id"])
data class Record(val scope: String, val kind: String, val id: String, val json: String) {
    fun value(): JSONObject = JSONObject(json)
}

@Entity(tableName = "pending", primaryKeys = ["scope", "id"])
data class Pending(
    val scope: String,
    val id: String,
    val job: String,
    val json: String,
    val audioPath: String? = null,
    val created: Long = System.currentTimeMillis(),
    val error: String = "",
)

@Dao
interface DiaryDao {
    @Query("SELECT * FROM records WHERE scope = :scope")
    fun watch(scope: String): Flow<List<Record>>

    @Query("SELECT * FROM records WHERE scope = :scope")
    suspend fun records(scope: String): List<Record>

    @Query("SELECT * FROM records WHERE scope = :scope AND kind = :kind AND id = :id")
    suspend fun find(scope: String, kind: String, id: String): Record?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: Record)

    @Query("DELETE FROM records WHERE scope = :scope AND kind = :kind AND id = :id")
    suspend fun delete(scope: String, kind: String, id: String)

    @Query("SELECT * FROM pending WHERE scope = :scope ORDER BY created")
    suspend fun pending(scope: String): List<Pending>

    @Query("SELECT * FROM pending WHERE scope = :scope ORDER BY created")
    fun watchPending(scope: String): Flow<List<Pending>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueue(value: Pending)

    @Query("DELETE FROM pending WHERE scope = :scope AND id = :id")
    suspend fun acknowledge(scope: String, id: String)
}

@Database(entities = [Record::class, Pending::class], version = 1, exportSchema = true)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun diary(): DiaryDao
}

class DiaryApplication : Application() {
    lateinit var repository: DiaryRepository

    override fun onCreate() {
        super.onCreate()
        repository = DiaryRepository(this)
        repository.schedule()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            RecordingService.recover(this@DiaryApplication, repository)
            repository.connection()?.let { repository.reminders.restore(it.scope) }
        }
    }
}

fun Context.repository(): DiaryRepository = (applicationContext as DiaryApplication).repository

class DiaryRepository(private val context: Context) {
    val settings = Settings(context)
    val database = Room.databaseBuilder(context, DiaryDatabase::class.java, "diary.db").build()
    val dao = database.diary()
    val reminders = Reminders(context, dao)
    private val mutex = Mutex()

    fun connection(): Connection? = settings.connection()

    suspend fun connect(address: String, port: String, token: String): Connection {
        require(token.length >= 32 && token.none { it.isWhitespace() }) { "Token 至少 32 个字符，不能包含空白" }
        val candidate = Connection(ServerAddress.parse(address, port).toString(), token, "")
        val value =
            DiaryApi(candidate)
                .post(
                    "android_connect",
                    JSONObject().put("device_id", settings.deviceId).put("protocol_version", 1),
                )
        require(value.optInt("protocol_version") == 1) { "服务端协议版本不支持" }
        val next = candidate.copy(instance = value.getString("instance_id"))
        connection()?.let { if (it.scope != next.scope) reminders.cancelAll(it.scope) }
        settings.save(next)
        kick()
        return next
    }

    suspend fun capture(
        text: String?,
        audio: File? = null,
        scope: String? = null,
        id: String = newId(),
        captured: String = ZonedDateTime.now().toOffsetDateTime().toString(),
        targetKind: String = "general",
        targetId: String? = null,
    ) {
        val server = scope ?: connection()?.scope ?: error("请先连接你的服务")
        val payload =
            JSONObject()
                .put("capture_id", id)
                .put("captured_at", captured)
                .put("timezone", java.time.ZoneId.systemDefault().id)
                .put("kind", if (audio != null) "audio" else "text")
                .put("target_kind", targetKind)
        targetId?.let { payload.put("target_id", it) }
        if (audio == null) payload.put("text", text)
        database.withTransaction {
            // Recovery may discover an already queued recording. Preserve its original receipt.
            if (dao.find(server, "run", id) == null) {
                dao.enqueue(
                    Pending(server, id, "android_capture", payload.toString(), audio?.absolutePath)
                )
                dao.put(
                    Record(
                        server,
                        "run",
                        id,
                        JSONObject()
                            .put("id", id)
                            .put("status", "local")
                            .put("text", text ?: "语音记录")
                            .put("captured_at", captured)
                            .toString(),
                    )
                )
            }
        }
        kick()
    }

    suspend fun update(document: JSONObject, action: String, times: JSONObject? = null) {
        val server = connection() ?: error("请先连接服务")
        val payload =
            JSONObject()
                .put("operation_id", newId())
                .put("kind", document.getString("kind"))
                .put("id", document.getString("id"))
                .put("action", action)
                .put("expected_revision", document.getInt("revision"))
        times?.keys()?.forEach { payload.put(it, times.get(it)) }
        dao.enqueue(
            Pending(
                server.scope,
                payload.getString("operation_id"),
                "android_update",
                payload.toString(),
            )
        )
        if (document.str("kind") == "task")
            reminders.suppress(server.scope, document.getString("id"))
        kick()
    }

    private suspend fun putRun(scope: String, value: JSONObject) {
        val id = value.getString("id")
        val previous = dao.find(scope, "run", id)?.value()
        if (previous == null || value.optInt("revision") >= previous.optInt("revision")) {
            dao.put(Record(scope, "run", id, value.toString()))
        }
    }

    suspend fun loadSource(id: String) {
        val server = connection() ?: return
        val value =
            DiaryApi(server).post("android_read", JSONObject().put("kind", "run").put("id", id))
        database.withTransaction { putRun(server.scope, value) }
    }

    suspend fun retryPending(id: String) {
        val scope = connection()?.scope ?: return
        dao.pending(scope)
            .find { it.id == id }
            ?.let { pending ->
                val body = JSONObject(pending.json)
                if (pending.job == "android_update" && pending.error.contains("内容已有更新")) {
                    val server = connection() ?: return
                    val latest =
                        DiaryApi(server)
                            .post(
                                "android_read",
                                JSONObject()
                                    .put("kind", body.getString("kind"))
                                    .put("id", body.getString("id")),
                            )
                    val nextId = newId()
                    body
                        .put("operation_id", nextId)
                        .put("expected_revision", latest.getInt("revision"))
                    database.withTransaction {
                        dao.acknowledge(scope, pending.id)
                        dao.enqueue(pending.copy(id = nextId, json = body.toString(), error = ""))
                    }
                } else dao.enqueue(pending.copy(error = ""))
            }
        kick()
    }

    suspend fun synchronize() = mutex.withLock {
        val server = connection() ?: return@withLock
        val api = DiaryApi(server)
        // Verify instance before uploading offline data even when the origin was reused.
        val identity = api.post("android_connect", JSONObject().put("device_id", settings.deviceId))
        if (identity.getString("instance_id") != server.instance)
            throw ApiFailure(409, "服务器工作区已改变，请重新连接；旧记录仍保留在本机")
        for (pending in dao.pending(server.scope)) {
            if (pending.error.isNotEmpty()) continue
            try {
                val body = JSONObject(pending.json)
                pending.audioPath?.let {
                    body.put("audio", Base64.encodeToString(File(it).readBytes(), Base64.NO_WRAP))
                }
                val result = api.post(pending.job, body)
                database.withTransaction {
                    when (pending.job) {
                        "android_capture" -> putRun(server.scope, result)
                        "android_update" ->
                            if (body.getString("kind") == "run") putRun(server.scope, result)
                            else
                                dao.put(
                                    Record(
                                        server.scope,
                                        body.getString("kind"),
                                        body.getString("id"),
                                        result.toString(),
                                    )
                                )
                    }
                    dao.acknowledge(server.scope, pending.id)
                }
                // Keep local originals: an accepted upload is not a reason to discard user audio.
            } catch (failure: ApiFailure) {
                if (failure.code == 401 || failure.code >= 500) throw failure
                dao.enqueue(pending.copy(error = failure.message ?: "需要处理"))
            }
        }
        var more: Boolean
        do {
            val cursor = dao.find(server.scope, "state", "cursor")?.value()?.str("cursor")
            val sync =
                api.post(
                    "android_sync",
                    JSONObject().put("device_id", settings.deviceId).apply {
                        cursor?.let { put("cursor", it) }
                    },
                )
            database.withTransaction {
                if (sync.optBoolean("reset")) {
                    dao.records(server.scope)
                        .filter { it.kind in listOf("task", "idea") }
                        .forEach { dao.delete(server.scope, it.kind, it.id) }
                }
                sync.optJSONArray("documents")?.objects()?.forEach {
                    dao.put(
                        Record(
                            server.scope,
                            it.getString("kind"),
                            it.getString("id"),
                            it.toString(),
                        )
                    )
                }
                sync.optJSONArray("deleted")?.objects()?.forEach {
                    dao.delete(server.scope, it.getString("kind"), it.getString("id"))
                }
                sync.optJSONArray("runs")?.objects()?.forEach {
                    putRun(server.scope, it)
                }
                dao.put(
                    Record(
                        server.scope,
                        "state",
                        "cursor",
                        JSONObject().put("cursor", sync.getString("cursor")).toString(),
                    )
                )
            }
            reminders.replace(
                server.scope,
                sync.optJSONArray("reminders")?.objects().orEmpty(),
                dao.pending(server.scope),
            )
            more = sync.optBoolean("has_more")
        } while (more)
        val journals = api.post("android_read", JSONObject().put("kind", "journal"))
        journals.optJSONArray("items")?.objects()?.forEach { value ->
            dao.put(
                Record(
                    server.scope,
                    "journal",
                    value.str("date", value.str("id")),
                    value.toString(),
                )
            )
        }
    }

    suspend fun readJournal(date: String): String {
        val server = connection() ?: return ""
        val result =
            DiaryApi(server)
                .post("android_read", JSONObject().put("kind", "journal").put("date", date))
        return result.str("content")
    }

    suspend fun observeRun(id: String, onSnapshot: (JSONObject) -> Unit) {
        val server = connection() ?: return
        try {
            DiaryApi(server).events(id).collect { chunk ->
                if (chunk.optJSONObject("metadata")?.str("kind") == "run_snapshot") {
                    val value = chunk.optJSONObject("chunk") ?: return@collect
                    database.withTransaction { putRun(server.scope, value) }
                    onSnapshot(value)
                }
            }
        } finally {
            kick()
        }
    }

    fun kick() {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "diary-sync",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build(),
            )
    }

    fun schedule() {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "diary-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build(),
            )
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            applicationContext.repository().synchronize()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.code == 401 || e.code == 409) Result.failure() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
}
