package io.agentscope.reme.diary

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

@Composable
internal fun Home(
    records: List<Record>,
    runs: List<Record>,
    conversation: () -> Unit,
    open: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "把此刻，\n慢慢说给我听。",
                fontSize = 34.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("留一点空间给自己", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("今天有什么想法？", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "一句灵感、一件小事，或一个想开始的计划。\n我会记下，也会帮你接着想。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Waveform(0.1f, false, Modifier.fillMaxWidth().height(32.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(
                    "待办",
                    records.count { it.kind == "task" && it.value().str("status") == "open" },
                    Modifier.weight(1f),
                ) {
                    open("tasks")
                }
                Metric("生长中的想法", records.count { it.kind == "idea" }, Modifier.weight(1f)) {
                    open("ideas")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近的对话",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = conversation) { Text("查看全部") }
            }
        }
        if (runs.isEmpty())
            item { Text("你的第一段记录，从下面的麦克风开始。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(runs.take(2), key = { it.id }) { record ->
            val run = record.value()
            Surface(
                onClick = conversation,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(run.str("text", "语音记录").take(90), maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        statusText(run.str("status")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Metric(label: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                count.toString().padStart(2, '0'),
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun VoiceComposer(
    text: String,
    edit: (String) -> Unit,
    recording: RecordingState,
    playing: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onStopPlaying: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recording.active) {
                Text(
                    "正在听 · ${recording.seconds / 60}:${(recording.seconds % 60).toString().padStart(2, '0')}",
                    color = MaterialTheme.colorScheme.primary,
                )
                Waveform(recording.level, true, Modifier.fillMaxWidth().height(38.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    Button(onClick = onStop) { Text("完成并保存") }
                }
            } else {
                if (recording.error.isNotEmpty())
                    Text(
                        recording.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledIconButton(
                        onClick = if (playing) onStopPlaying else onRecord,
                        modifier =
                            Modifier.size(62.dp).semantics {
                                contentDescription = if (playing) "停止朗读" else "开始录音"
                            },
                        shape = CircleShape,
                    ) {
                        if (playing)
                            Box(
                                Modifier.size(18.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onPrimary,
                                        RoundedCornerShape(4.dp),
                                    )
                            )
                        else MicIcon(Modifier.size(26.dp))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = edit,
                        placeholder = { Text("也可以写下来…") },
                        modifier = Modifier.weight(1f).testTag("composer_input"),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                    )
                    if (text.isNotBlank())
                        IconButton(onClick = onSend) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                "发送记录",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (playing) "正在朗读 · 点击停止后继续说" else "轻点开始录音 · 你的记录只属于你",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun Conversation(
    runs: List<Record>,
    focus: String?,
    speak: (JSONObject) -> Unit,
    original: (JSONObject) -> Unit,
    retry: (JSONObject) -> Unit,
    cancel: (JSONObject) -> Unit,
    correct: (JSONObject, String) -> Unit,
    modifier: Modifier,
) {
    var correction by remember { mutableStateOf<JSONObject?>(null) }
    var correctedText by remember { mutableStateOf("") }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(runs.size, focus) {
        if (runs.isNotEmpty())
            state.animateScrollToItem(
                runs.reversed().indexOfFirst { it.id == focus }.takeIf { it >= 0 }
                    ?: (runs.size - 1)
            )
    }
    LazyColumn(
        modifier,
        state = state,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (runs.isEmpty()) item { EmptyState("想到什么，就说什么", "我会把零散的想法慢慢连起来") }
        items(runs.reversed(), key = { it.id }) {
            val run = it.value()
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "我 · ${run.str("captured_at").take(16).replace('T', ' ')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer { Text(run.str("text", "语音记录")) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "ReMe",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                val answer = run.str("answer", run.str("preview"))
                if (answer.isNotEmpty()) SelectionContainer { Text(answer, lineHeight = 26.sp) }
                else
                    Text(
                        statusText(run.str("status")),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (run.str("kind") == "audio")
                    Row {
                        TextButton(onClick = { original(run) }) { Text("播放原始录音") }
                        if (run.str("text").isNotBlank())
                            TextButton(
                                onClick = {
                                    correctedText = run.str("text")
                                    correction = run
                                }
                            ) {
                                Text("修正转写")
                            }
                    }
                when (run.str("status")) {
                    "completed" -> TextButton(onClick = { speak(run) }) { Text("朗读回复") }
                    "failed",
                    "cancelled" ->
                        Row {
                            Text(
                                "本次处理未完成，原始记录已保存",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { retry(run) }) { Text("重试") }
                        }
                    "local" -> Text("已保存在本机，联网后上传", style = MaterialTheme.typography.labelSmall)
                    else -> TextButton(onClick = { cancel(run) }) { Text("停止整理") }
                }
            }
        }
    }
    correction?.let { run ->
        AlertDialog(
            onDismissRequest = { correction = null },
            title = { Text("修正语音转写") },
            text = {
                OutlinedTextField(
                    correctedText,
                    { correctedText = it.take(20000) },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        correct(run, correctedText)
                        correction = null
                    },
                    enabled = correctedText.isNotBlank(),
                ) {
                    Text("保存并重新整理")
                }
            },
            dismissButton = { TextButton(onClick = { correction = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun DocumentList(
    kind: String,
    records: List<Record>,
    pending: List<Pending>,
    open: (String) -> Unit,
    complete: (JSONObject) -> Unit,
) {
    val blocked =
        pending.filter { it.job == "android_update" }.map { JSONObject(it.json).str("id") }.toSet()
    val docs =
        records
            .filter { it.kind == kind }
            .sortedWith(
                compareBy(
                    { it.value().str("status") != "open" },
                    { it.value().str("remind_at", "9999") },
                )
            )
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageTitle(
                if (kind == "task") "给行动，一个时间。" else "让想法，慢慢生长。",
                if (kind == "task") "从你说过的话里，找到下一步。" else "没有截止日期，随时回来继续聊。",
            )
        }
        if (docs.isEmpty())
            item { EmptyState(if (kind == "task") "还没有待办" else "灵感会在这里汇合", "在对话里记下，整理后会自动出现") }
        items(docs, key = { it.id }) { record ->
            val doc = record.value()
            val waiting = record.id in blocked
            Surface(
                onClick = { open(record.id) },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                    if (kind == "task")
                        Checkbox(
                            checked =
                                doc.str("status") == "done" ||
                                    pending.any {
                                        it.job == "android_update" &&
                                            JSONObject(it.json).str("id") == record.id &&
                                            JSONObject(it.json).str("action") == "complete"
                                    },
                            onCheckedChange = { if (it && !waiting) complete(doc) },
                            enabled = doc.str("status") == "open" && !waiting,
                        )
                    Column(Modifier.weight(1f)) {
                        Text(doc.str("title"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            doc.str("content").substringBefore("\n## AI").take(100),
                            maxLines = 3,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (waiting) "更改待同步"
                            else if (kind == "task")
                                formatTime(doc.str("remind_at")).ifEmpty { "时间待定" }
                            else ideaStatus(doc.str("status")),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DocumentDetail(
    value: JSONObject,
    action: (String) -> Unit,
    discuss: () -> Unit,
    reschedule: () -> Unit,
    source: (String) -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PageTitle(
            value.str("title"),
            if (value.str("kind") == "idea") ideaStatus(value.str("status"))
            else formatTime(value.str("remind_at")).ifEmpty { "还没有安排提醒时间" },
        )
        Markdown(value.str("content"))
        Button(onClick = discuss, modifier = Modifier.fillMaxWidth()) {
            Text("继续聊这个${if (value.str("kind") == "idea") "想法" else "待办"}")
        }
        if (value.str("kind") == "task") {
            OutlinedButton(onClick = reschedule, modifier = Modifier.fillMaxWidth()) {
                Text("调整提醒时间")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = {
                        action(if (value.str("status") == "done") "reopen" else "complete")
                    }
                ) {
                    Text(if (value.str("status") == "done") "重新打开" else "标记完成")
                }
                if (value.str("status") == "open")
                    TextButton(onClick = { action("cancel") }) { Text("取消待办") }
            }
        } else
            OutlinedButton(
                onClick = { action(if (value.str("status") == "paused") "resume" else "pause") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (value.str("status") == "paused") "继续预研" else "暂停预研")
            }
        Text("关联记录", style = MaterialTheme.typography.titleMedium)
        value.optJSONArray("source_ids")?.let { ids ->
            repeat(ids.length()) { index ->
                TextButton(onClick = { source(ids.getString(index)) }) {
                    Text("查看第 ${index + 1} 条来源对话")
                }
            }
        }
    }
}

@Composable
internal fun Journals(records: List<Record>, open: (String) -> Unit) {
    val journals = records.filter { it.kind == "journal" }.sortedByDescending { it.id }
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageTitle("说过的话，\n都成为了日子。", "按天整理，也保留最初的表达。") }
        if (journals.isEmpty()) item { EmptyState("日记还在等你", "完成一次记录，就会生成今天的日记") }
        items(journals, key = { it.id }) { record ->
            Surface(
                onClick = { open(record.id) },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(record.id, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        record
                            .value()
                            .str("content")
                            .lineSequence()
                            .filter {
                                !it.startsWith("#") && !it.startsWith("<!--") && it.isNotBlank()
                            }
                            .joinToString(" ")
                            .take(160),
                        maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConnectionScreen(
    connection: Connection?,
    busy: Boolean,
    connect: (String, String, String) -> Unit,
    pending: List<Pending>,
    retry: (Pending) -> Unit,
    discard: (Pending) -> Unit,
) {
    val url = remember(connection?.origin) { connection?.origin?.let { it.toHttpUrl() } }
    var address by
        rememberSaveable(connection?.origin) {
            mutableStateOf(
                url?.let {
                    "${it.scheme}://${if (it.host.contains(':')) "[${it.host}]" else it.host}"
                } ?: ""
            )
        }
    var port by
        rememberSaveable(connection?.origin) { mutableStateOf(url?.port?.toString() ?: "2333") }
    var token by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmDiscard by remember { mutableStateOf<Pending?>(null) }
    var connectAfterPermission by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf("") }
    val localNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) {
                if (connectAfterPermission) {
                    connect(address, port, token)
                    token = ""
                }
                permissionError = ""
                connectAfterPermission = false
            } else permissionError = "连接本机或局域网服务需要允许局域网访问"
        }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageTitle("连接你的日记空间", "填写你自己的 Python 服务地址。")
        OutlinedTextField(
            address,
            { address = it },
            label = { Text("服务地址") },
            placeholder = { Text("https://diary.example.com") },
            modifier = Modifier.fillMaxWidth().testTag("server_address"),
            singleLine = true,
        )
        OutlinedTextField(
            port,
            { port = it },
            label = { Text("端口") },
            modifier = Modifier.fillMaxWidth().testTag("server_port"),
            singleLine = true,
        )
        OutlinedTextField(
            token,
            { token = it },
            label = { Text(if (connection == null) "Token" else "新 Token（留空沿用当前）") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("server_token"),
            singleLine = true,
        )
        if (address.startsWith("http://"))
            Text(
                "连接未加密",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
            )
        Button(
            onClick = {
                val host = runCatching { ServerAddress.parse(address, port).host }.getOrDefault("")
                if (
                    android.os.Build.VERSION.SDK_INT >= 37 &&
                        ServerAddress.isLocal(host) &&
                        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    connectAfterPermission = true
                    localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    connect(address, port, token)
                    token = ""
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (busy) "正在连接…" else "保存并连接")
        }
        if (permissionError.isNotEmpty())
            Text(permissionError, color = MaterialTheme.colorScheme.error)
        if (android.os.Build.VERSION.SDK_INT >= 37)
            TextButton(
                onClick = {
                    connectAfterPermission = false
                    localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            ) {
                Text("允许连接局域网服务")
            }
        HorizontalDivider()
        var autoRead by remember { mutableStateOf(context.repository().settings.autoRead) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动朗读本轮回复", modifier = Modifier.weight(1f))
            Switch(
                autoRead,
                {
                    autoRead = it
                    context.repository().settings.autoRead = it
                },
            )
        }
        var voice by remember { mutableStateOf(context.repository().settings.voice) }
        OutlinedTextField(
            voice,
            {
                voice = it
                context.repository().settings.voice = it
            },
            label = { Text("声音 ID（留空使用服务默认）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("系统提醒", style = MaterialTheme.typography.titleMedium)
        Text(
            if (context.repository().reminders.exactAllowed()) "已允许准时提醒"
            else "当前使用系统普通提醒，可能延后。可在系统设置中允许准时提醒。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val notificationPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                context.repository().kick()
            }
        TextButton(
            onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        ) {
            Text("允许通知")
        }
        TextButton(
            onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
            }
        ) {
            Text("允许准时提醒")
        }
        if (pending.any { it.error.isNotEmpty() }) {
            Text("需要处理的同步项", style = MaterialTheme.typography.titleMedium)
            pending
                .filter { it.error.isNotEmpty() }
                .forEach { value ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(value.error)
                            Row {
                                TextButton(onClick = { retry(value) }) {
                                    Text(if (value.error.contains("内容已有更新")) "按最新版本提交" else "重试")
                                }
                                TextButton(onClick = { confirmDiscard = value }) { Text("放弃此更改") }
                            }
                        }
                    }
                }
        }
    }
    confirmDiscard?.let { value ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("放弃待同步更改？") },
            text = { Text("本机录音原文件会保留。这条操作将不再提交。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard(value)
                        confirmDiscard = null
                    }
                ) {
                    Text("放弃更改")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("返回") } },
        )
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Text(title, fontSize = 29.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 24.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun Markdown(content: String) {
    val primary = MaterialTheme.colorScheme.primary
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content
                .lineSequence()
                .filter { !it.startsWith("<!--") && it.isNotBlank() }
                .forEach { line ->
                    val plain =
                        line
                            .removePrefix("### ")
                            .removePrefix("## ")
                            .removePrefix("# ")
                            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
                            .replace(Regex("^[-*] "), "• ")
                    val text = buildAnnotatedString {
                        var position = 0
                        Regex("\\[([^]]+)]\\(([^)]+)\\)").findAll(plain).forEach { match ->
                            append(plain.substring(position, match.range.first))
                            val label = match.groupValues[1]
                            val link = match.groupValues[2]
                            if (link.startsWith("https://") || link.startsWith("http://")) {
                                withLink(
                                    LinkAnnotation.Url(
                                        link,
                                        TextLinkStyles(SpanStyle(color = primary)),
                                    )
                                ) {
                                    append(label)
                                }
                            } else append(label)
                            position = match.range.last + 1
                        }
                        append(plain.substring(position))
                    }
                    Text(
                        text,
                        style =
                            if (line.startsWith('#')) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyLarge,
                        lineHeight = 27.sp,
                    )
                }
        }
    }
}

@Composable
internal fun MicIcon(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color,
            Offset(w * .34f, 0f),
            Size(w * .32f, h * .60f),
            androidx.compose.ui.geometry.CornerRadius(w * .16f),
        )
        drawArc(
            color,
            0f,
            180f,
            false,
            Offset(w * .18f, h * .22f),
            Size(w * .64f, h * .55f),
            style = Stroke(w * .07f),
        )
        drawLine(
            color,
            Offset(w * .5f, h * .77f),
            Offset(w * .5f, h * .98f),
            w * .07f,
            StrokeCap.Round,
        )
    }
}

@Composable
internal fun Waveform(level: Float, live: Boolean, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        repeat(43) { i ->
            val wave =
                if (live) ((i * 37 % 17) / 17f * .7f + .3f) * level.coerceAtLeast(.05f)
                else (.1f + .15f * absSin(i.toFloat()))
            val height = (size.height * wave).coerceAtLeast(3.dp.toPx())
            val x = size.width * (i + 1) / 44
            drawLine(
                color.copy(alpha = if (live) .9f else .35f),
                Offset(x, (size.height - height) / 2),
                Offset(x, (size.height + height) / 2),
                3.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

private fun absSin(value: Float) = kotlin.math.abs(sin(value))

@Composable
internal fun TabIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        when (index) {
            0 -> {
                drawRoundRect(
                    color,
                    Offset(w * .3f, 0f),
                    Size(w * .4f, h * .65f),
                    androidx.compose.ui.geometry.CornerRadius(w * .2f),
                )
                drawArc(
                    color,
                    0f,
                    180f,
                    false,
                    Offset(w * .1f, h * .15f),
                    Size(w * .8f, h * .65f),
                    style = Stroke(stroke),
                )
                drawLine(color, Offset(w * .5f, h * .8f), Offset(w * .5f, h), stroke)
            }
            1 -> {
                drawRoundRect(
                    color,
                    Offset(2f, 1f),
                    Size(w - 4f, h - 2f),
                    androidx.compose.ui.geometry.CornerRadius(3f),
                    style = Stroke(stroke),
                )
                drawLine(color, Offset(w * .35f, 1f), Offset(w * .35f, h - 1f), stroke)
            }
            2 ->
                repeat(3) { i ->
                    val y = h * (i + 1) / 4
                    drawCircle(color, 2f, Offset(w * .1f, y))
                    drawLine(
                        color,
                        Offset(w * .35f, y),
                        Offset(w * .95f, y),
                        stroke,
                        StrokeCap.Round,
                    )
                }
            else -> {
                drawCircle(color, w * .33f, Offset(w * .5f, h * .35f), style = Stroke(stroke))
                drawLine(
                    color,
                    Offset(w * .35f, h * .8f),
                    Offset(w * .65f, h * .8f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * .4f, h * .98f),
                    Offset(w * .6f, h * .98f),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
    }
}

internal fun statusText(value: String) =
    when (value) {
        "local" -> "已保存在本机"
        "preparing",
        "queued" -> "已保存，等待整理"
        "transcribing" -> "正在转写语音"
        "thinking" -> "正在关联历史记录"
        "replying" -> "正在回复"
        "completed" -> "已整理"
        "failed" -> "暂未完成，可重试"
        "cancelled" -> "已停止整理"
        else -> value
    }

internal fun ideaStatus(value: String) =
    when (value) {
        "collecting" -> "持续收集"
        "researching" -> "正在预研"
        "ready_for_discussion" -> "已准备好，等你来聊"
        "paused" -> "已暂停预研"
        else -> value
    }

internal fun formatTime(value: String): String = runCatching {
    OffsetDateTime.parse(value)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}
    .getOrDefault("")

internal fun pickTime(context: android.content.Context, chosen: (String) -> Unit) {
    val now = ZonedDateTime.now()
    DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            chosen(
                                ZonedDateTime.of(
                                        year,
                                        month + 1,
                                        day,
                                        hour,
                                        minute,
                                        0,
                                        0,
                                        ZoneId.systemDefault(),
                                    )
                                    .toOffsetDateTime()
                                    .toString()
                            )
                        },
                        now.hour,
                        now.minute,
                        true,
                    )
                    .show()
            },
            now.year,
            now.monthValue - 1,
            now.dayOfMonth,
        )
        .show()
}
