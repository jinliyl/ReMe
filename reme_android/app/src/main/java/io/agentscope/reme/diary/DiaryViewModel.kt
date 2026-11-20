package io.agentscope.reme.diary

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    val repo = application.repository()
    val connection = MutableStateFlow(repo.connection())
    val records =
        connection
            .flatMapLatest { it?.let { repo.dao.watch(it.scope) } ?: flowOf(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pending =
        connection
            .flatMapLatest { it?.let { repo.dao.watchPending(it.scope) } ?: flowOf(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val error = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val player = VoicePlayer(application)
    val journal = MutableStateFlow("")
    val autoReplyTo = MutableStateFlow<String?>(null)
    private var playback: Job? = null

    fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "暂时无法完成，请稍后重试"
        }
    }

    fun connect(address: String, port: String, token: String) = launch {
        busy.value = true
        try {
            val current = connection.value
            val origin = ServerAddress.parse(address, port).toString()
            val effective = token.ifEmpty { current?.takeIf { it.origin == origin }?.token ?: "" }
            connection.value = repo.connect(address, port, effective)
            error.value = ""
            repo.synchronize()
        } finally {
            busy.value = false
        }
    }

    fun send(text: String, target: JSONObject? = null, onSaved: () -> Unit = {}) = launch {
        if (text.isBlank()) return@launch
        val id = newId()
        repo.capture(
            text.trim(),
            id = id,
            targetKind = target?.str("kind") ?: "general",
            targetId = target?.str("id"),
        )
        autoReplyTo.value = id
        onSaved()
    }

    fun action(value: JSONObject, action: String, times: JSONObject? = null) = launch {
        repo.update(value, action, times)
    }

    fun sync() = launch {
        busy.value = true
        try {
            repo.synchronize()
            error.value = ""
        } finally {
            busy.value = false
        }
    }

    suspend fun foreground() {
        while (currentCoroutineContext().isActive) {
            try {
                repo.synchronize()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "暂时离线，记录会在网络恢复后同步"
            }
            delay(
                if (
                    records.value.any {
                        it.kind == "run" &&
                            it.value().str("status") !in listOf("completed", "failed", "cancelled")
                    }
                )
                    5000
                else 30000
            )
        }
    }

    suspend fun observe(id: String) {
        try {
            repo.observeRun(id) {}
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            /* HTTP sync recovers after SSE disconnect. */
        }
    }

    fun record(target: JSONObject? = null) {
        stopSpeaking()
        val id = newId()
        autoReplyTo.value = id
        getApplication<Application>()
            .startForegroundService(
                Intent(getApplication(), RecordingService::class.java)
                    .putExtra("capture_id", id)
                    .putExtra("target_kind", target?.str("kind") ?: "general")
                    .putExtra("target_id", target?.str("id"))
            )
    }

    fun stopRecording(cancel: Boolean = false) {
        if (cancel) autoReplyTo.value = null
        getApplication<Application>()
            .startService(
                Intent(getApplication(), RecordingService::class.java)
                    .setAction(if (cancel) "cancel" else "stop")
            )
    }

    fun speak(run: JSONObject) {
        stopSpeaking()
        playback = launch {
            connection.value?.let { player.speak(DiaryApi(it), run, repo.settings.voice) }
        }
    }

    fun original(run: JSONObject) {
        stopSpeaking()
        playback = launch {
            connection.value?.let { player.original(DiaryApi(it), run.getString("id")) }
        }
    }

    fun stopSpeaking() {
        playback?.cancel()
        playback = null
        player.stop()
    }

    fun loadSource(id: String) = launch { repo.loadSource(id) }

    fun loadJournal(date: String) = launch {
        journal.value =
            records.value.find { it.kind == "journal" && it.id == date }?.value()?.str("content")
                ?: ""
        journal.value = repo.readJournal(date)
    }

    fun discardPending(value: Pending) = launch {
        repo.dao.acknowledge(value.scope, value.id)
        repo.synchronize()
    }

    override fun onCleared() {
        stopSpeaking()
    }
}
