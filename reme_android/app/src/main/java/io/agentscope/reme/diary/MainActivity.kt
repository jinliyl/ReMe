package io.agentscope.reme.diary

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import java.time.*
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared =
            if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)
            else null
        val taskId = intent.getStringExtra("task_id")
        setContent { DiaryTheme { DiaryApp(shared, taskId) } }
    }
}

private val Forest = Color(0xFF2C6E62)
private val Paper = Color(0xFFF7F5F0)
private val Ink = Color(0xFF243B35)

@Composable
fun DiaryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme =
            if (dark)
                darkColorScheme(
                    primary = Color(0xFF9FD7C3),
                    secondary = Color(0xFFC3CDB9),
                    background = Color(0xFF121C18),
                    surface = Color(0xFF1D2923),
                )
            else
                lightColorScheme(
                    primary = Forest,
                    secondary = Color(0xFF777B57),
                    background = Paper,
                    surface = Color(0xFFFFFDF8),
                    onBackground = Ink,
                    surfaceVariant = Color(0xFFE9EEE6),
                    onSurfaceVariant = Color(0xFF65746A),
                ),
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryApp(shared: String?, taskId: String?, vm: DiaryViewModel = viewModel()) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val recording by RecordingService.state.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val stack = remember { mutableStateListOf("home") }
    var tab by rememberSaveable { mutableStateOf("home") }
    var draft by rememberSaveable { mutableStateOf(shared ?: "") }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    val autoReplyTo by vm.autoReplyTo.collectAsStateWithLifecycle()
    var sourceFocus by remember { mutableStateOf<String?>(null) }
    val target = records.find { it.id == targetId && it.kind in listOf("task", "idea") }?.value()
    val runs =
        records.filter { it.kind == "run" }.sortedByDescending { it.value().str("captured_at") }
    val active = runs.firstOrNull {
        it.value().str("status") in listOf("queued", "transcribing", "thinking", "replying")
    }
    val microphone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) {
                vm.record(target)
            } else vm.error.value = "需要麦克风权限才能录音，也可以用文字记录"
        }
    LaunchedEffect(connection?.scope) {
        if (connection == null) {
            if (stack.last() != "settings") stack.add("settings")
        } else lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.foreground() }
    }
    LaunchedEffect(taskId) { taskId?.let { stack.add("task/$it") } }
    LaunchedEffect(active?.id, active?.value()?.optInt("attempt")) {
        active?.let { vm.observe(it.id) }
    }
    LaunchedEffect(runs, autoReplyTo) {
        val value = runs.find { it.id == autoReplyTo }?.value()
        if (value?.str("status") == "completed" && !recording.active) {
            vm.autoReplyTo.value = null
            if (vm.repo.settings.autoRead) vm.speak(value)
        }
    }
    DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) vm.stopSpeaking()
            }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            vm.stopSpeaking()
        }
    }
    fun record() {
        if (connection == null) {
            stack.add("settings")
            return
        }
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            vm.record(target)
        } else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stack.size > 1)
                    IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                Text(
                    "ReMe",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    onClick = { vm.sync() },
                ) {
                    Text(
                        if (busy) "同步中"
                        else if (pending.isNotEmpty()) "${pending.size} 条待同步"
                        else if (connection == null) "未连接" else "私人日记",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                IconButton(onClick = { stack.add("settings") }) {
                    Icon(Icons.Default.Settings, "连接设置")
                }
            }
        },
        bottomBar = {
            if (stack.size == 1)
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    listOf("home" to "记录", "journals" to "日记", "tasks" to "待办", "ideas" to "想法")
                        .forEachIndexed { index, (key, label) ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_$key"),
                                selected = tab == key,
                                onClick = {
                                    tab = key
                                    stack[0] = key
                                },
                                label = { Text(label) },
                                icon = { TabIcon(index) },
                            )
                        }
                }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            if (error.isNotEmpty())
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.error.value = "" }) {
                            Icon(Icons.Default.Close, "关闭提示")
                        }
                    }
                }
            NavDisplay(
                backStack = stack,
                onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                modifier = Modifier.weight(1f),
                entryProvider = { route ->
                    NavEntry(route) {
                        when {
                            route == "settings" ->
                                ConnectionScreen(
                                    connection,
                                    busy,
                                    { a, p, t -> vm.connect(a, p, t) },
                                    pending,
                                    { vm.launch { vm.repo.retryPending(it.id) } },
                                    { vm.discardPending(it) },
                                )
                            route == "home" || route == "conversation" -> {
                                Column(Modifier.fillMaxSize()) {
                                    if (route == "home")
                                        Home(
                                            records,
                                            runs,
                                            { stack.add("conversation") },
                                            { stack.add(it) },
                                            Modifier.weight(1f),
                                        )
                                    else
                                        Conversation(
                                            runs,
                                            sourceFocus,
                                            { vm.speak(it) },
                                            { vm.original(it) },
                                            { vm.action(it.put("kind", "run"), "retry") },
                                            { vm.action(it.put("kind", "run"), "cancel") },
                                            { run, text ->
                                                vm.action(
                                                    run.put("kind", "run"),
                                                    "correct",
                                                    JSONObject().put("text", text),
                                                )
                                            },
                                            Modifier.weight(1f),
                                        )
                                    if (target != null)
                                        Row(
                                            Modifier.padding(horizontal = 24.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "继续讨论：${target.str("title")}",
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            IconButton(onClick = { targetId = null }) {
                                                Icon(Icons.Default.Close, "结束关联讨论")
                                            }
                                        }
                                    VoiceComposer(
                                        draft,
                                        { draft = it },
                                        recording,
                                        playing,
                                        onRecord = { record() },
                                        onStop = { vm.stopRecording() },
                                        onCancel = { vm.stopRecording(true) },
                                        onStopPlaying = {
                                            vm.autoReplyTo.value = null
                                            vm.stopSpeaking()
                                        },
                                        onSend = {
                                            if (connection == null) stack.add("settings")
                                            else {
                                                vm.send(draft, target) {
                                                    draft = ""
                                                    if (route == "home") stack.add("conversation")
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            route == "tasks" ->
                                DocumentList(
                                    "task",
                                    records,
                                    pending,
                                    { stack.add("task/$it") },
                                    { vm.action(it, "complete") },
                                )
                            route == "ideas" ->
                                DocumentList(
                                    "idea",
                                    records,
                                    pending,
                                    { stack.add("idea/$it") },
                                    {},
                                )
                            route == "journals" ->
                                Journals(records) { date ->
                                    vm.loadJournal(date)
                                    stack.add("journal/$date")
                                }
                            route.startsWith("journal/") -> {
                                val content by vm.journal.collectAsStateWithLifecycle()
                                Column(
                                    Modifier.verticalScroll(rememberScrollState()).padding(24.dp)
                                ) {
                                    PageTitle(route.substringAfter('/'), "记录过的每个瞬间，都有迹可循")
                                    Markdown(content)
                                }
                            }
                            else -> {
                                val kind = route.substringBefore('/')
                                val id = route.substringAfter('/')
                                val value = records.find { it.kind == kind && it.id == id }?.value()
                                if (value != null)
                                    DocumentDetail(
                                        value,
                                        { action -> vm.action(value, action) },
                                        {
                                            targetId = id
                                            stack.add("conversation")
                                        },
                                        {
                                            pickTime(context) { time ->
                                                vm.action(
                                                    value,
                                                    "reschedule",
                                                    JSONObject()
                                                        .put("remind_at", time)
                                                        .put("scheduled_at", time),
                                                )
                                            }
                                        },
                                        { sourceId ->
                                            sourceFocus = sourceId
                                            vm.loadSource(sourceId)
                                            stack.add("conversation")
                                            targetId = id
                                        },
                                    )
                                else EmptyState("正在同步这条记录", "连接服务后会自动恢复")
                            }
                        }
                    }
                },
            )
        }
    }
}
