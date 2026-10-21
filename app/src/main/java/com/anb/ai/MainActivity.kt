package com.anb.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Background = Color(0xFF06152F)
private val SurfaceDark = Color(0xFF0C2345)
private val Green = Color(0xFFFFC857)
private val TextPrimary = Color(0xFFF7F3E8)
private val TextMuted = Color(0xFFB5C5DD) // ANB_ROYAL_THEME_V16

data class ChatMessage(val text: String, val isUser: Boolean)

// ANB_ALL_CHAT_MEMORY_V15: complete transcript is stored only in app-private filesDir.
// No message-count cap is applied. Android may remove this data if the app is uninstalled.
private const val ALL_CHAT_HISTORY_FILE = "anb-ai-chat-history.json"

private fun loadAllChatHistory(context: android.content.Context): List<ChatMessage> {
    val file = java.io.File(context.filesDir, ALL_CHAT_HISTORY_FILE)
    if (!file.isFile) return listOf(
        ChatMessage(
            "Namaste! Main ANB AI hoon. Aapki chat history isi phone par local save rahegi.",
            false
        )
    )
    return try {
        val array = org.json.JSONArray(file.readText(Charsets.UTF_8))
        val result = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            ChatMessage(item.optString("text", ""), item.optBoolean("isUser", false))
        }
        result.ifEmpty { listOf(ChatMessage("Namaste! Main ANB AI hoon.", false)) }
    } catch (_: Exception) {
        listOf(ChatMessage("Saved chat history read nahi ho paayi. Nayi chat shuru kar sakte hain.", false))
    }
}

private fun saveAllChatHistory(context: android.content.Context, messages: List<ChatMessage>) {
    val array = org.json.JSONArray()
    messages.forEach { message ->
        val item = org.json.JSONObject()
        item.put("text", message.text)
        item.put("isUser", message.isUser)
        array.put(item)
    }
    val file = java.io.File(context.filesDir, ALL_CHAT_HISTORY_FILE)
    val temporary = java.io.File(context.filesDir, "$ALL_CHAT_HISTORY_FILE.tmp")
    try {
        temporary.writeText(array.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
    } catch (_: Exception) {
        temporary.delete()
    }
}

/** Keyword retrieval over the complete local transcript; no cloud service is used. */
private fun buildMemoryPrompt(question: String, history: List<ChatMessage>): String {
    if (history.isEmpty()) return question
    val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "what", "when", "where",
        "have", "from", "about", "your", "you", "are", "was", "were", "did",
        "hai", "hain", "tha", "thi", "aur", "kya", "kaise", "kab", "kahan",
        "mujhe", "batao", "bata", "mere", "meri", "mera", "pichle", "baat",
        "mein", "ke", "ki", "ka", "ko", "se", "par", "to"
    )
    val terms = Regex("[\\p{L}\\p{N}]{2,}")
        .findAll(question.lowercase()).map { it.value }
        .filter { it !in stopWords }.toSet()
    if (terms.isEmpty()) return question

    val scored = history.mapIndexedNotNull { index, message ->
        val words = Regex("[\\p{L}\\p{N}]{2,}")
            .findAll(message.text.lowercase()).map { it.value }.toSet()
        val score = terms.count { it in words }
        if (score > 0) index to score else null
    }
    val selected = linkedSetOf<Int>()
    (maxOf(0, history.size - 4) until history.size).forEach { selected.add(it) }
    scored.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        .take(8)
        .forEach { (index, _) ->
            selected.add(index)
            if (index > 0) selected.add(index - 1)
            if (index + 1 < history.size) selected.add(index + 1)
        }

    val excerpts = selected.sorted().takeLast(10).map { index ->
        val message = history[index]
        val role = if (message.isUser) "User" else "ANB AI"
        "$role: ${message.text.replace(Regex("\\s+"), " ").take(320)}"
    }
    val contextText = excerpts.joinToString("\n").take(2600)
    val promptLines = listOf(
        "You are ANB AI. Use the relevant saved conversation excerpts below when answering.",
        "If asked what the user said, asked, preferred, or what you answered earlier, use only evidence in these excerpts.",
        "Do not invent details that are not present. If the history does not contain the requested detail, say you could not find it in saved conversation.",
        "Use Hindi/Hinglish if the current question is Hindi/Hinglish.",
        "",
        "Relevant saved conversation excerpts:",
        contextText,
        "",
        "Current question:",
        question
    )
    return promptLines.joinToString("\n")
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green,
                    background = Background,
                    surface = SurfaceDark,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                ANBHome()
            }
        }
    }
}

@Composable
fun ANBHome() {
    val context = LocalContext.current
    val repository = remember { LocalAiRepository() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var historySearch by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(repository.isModelDownloaded(context)) }
    var aiLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var currentTab by remember { mutableStateOf("Home") }
    var soundOn by remember { mutableStateOf(true) }
    var vibrationOn by remember { mutableStateOf(true) }
    var animationsOn by remember { mutableStateOf(true) }
    var offlineOn by remember { mutableStateOf(true) }
    var status by remember {
        mutableStateOf(
            if (modelReady) "Model downloaded. Start local AI to begin." else "Local model not downloaded yet."
        )
    }
    // ANB_ALL_CHAT_MEMORY_V15: restore and preserve the full local transcript.
    var messages by remember { mutableStateOf(loadAllChatHistory(context)) }\n    // ANB_AI_CHAT_RESTORE_V22: restore a previously exported ANB AI transcript.
    val restoreChatLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("Could not read selected backup")
                val parsed = raw.lineSequence().mapNotNull { line ->
                    when {
                        line.startsWith("You: ") -> line.removePrefix("You: ").takeIf { it.isNotBlank() }
                            ?.let { ChatMessage(it, true) }
                        line.startsWith("ANB AI: ") -> line.removePrefix("ANB AI: ").takeIf { it.isNotBlank() }
                            ?.let { ChatMessage(it, false) }
                        else -> null
                    }
                }.toList()
                if (parsed.isEmpty()) {
                    status = "Backup file had no readable chat messages."
                } else {
                    messages = parsed
                    saveAllChatHistory(context, messages)
                    status = "Chat backup restored: ${parsed.size} messages."
                }
            } catch (_: Exception) {
                status = "Could not restore this file. Choose a text file exported from ANB AI."
            }
        }
    }

    val visibleMessages = if (historySearch.isBlank()) messages else messages.filter { it.text.contains(historySearch.trim(), ignoreCase = true) }

    LaunchedEffect(messages) {
        kotlinx.coroutines.delay(350)
        saveAllChatHistory(context, messages)
    }

    DisposableEffect(repository) {
        onDispose { repository.closeAsync() }
    }

    val sendMessage: () -> Unit = {
        val question = input.trim()
        val historyBeforeQuestion = messages
        if (question.isNotEmpty() && aiLoaded && !busy) {
            val prompt = buildMemoryPrompt(question, historyBeforeQuestion)
            messages = messages + ChatMessage(question, true)
            input = ""
            busy = true
            AiBackgroundService.start(context)
            scope.launch {
                messages = messages + ChatMessage("", false)
                try {
                    val answer = repository.ask(prompt) { partial ->
                        messages = messages.dropLast(1) + ChatMessage(partial, false)
                    }
                    messages = messages.dropLast(1) + ChatMessage(answer, false)
                    status = "Local AI ready."
                } catch (error: Exception) {
                    messages = messages.dropLast(1) + ChatMessage(
                        "Local AI error: ${error.message ?: "Please try again."}", false
                    )
                    status = "Local AI request failed. You can retry."
                } finally {
                    busy = false
                    AiBackgroundService.stop(context)
                }
            }
        } else if (question.isBlank()) {
            status = "Pehle apna sawal boliye ya type karein."
        } else if (!aiLoaded) {
            status = "Pehle local AI start karein."
        }
    }

    val navy = Color(0xFF04132D)
    val panel = Color(0xFF0A2145)
    val panelLight = Color(0xFF103263)
    val gold = Color(0xFFFFD36A)
    val goldDeep = Color(0xFFE8A928)
    val brightBlue = Color(0xFF087BFF)
    val textMain = Color(0xFFFFF8E9)
    val textSub = Color(0xFFB8C9E5)

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = gold, secondary = brightBlue, background = navy, surface = panel,
            onBackground = textMain, onSurface = textMain
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(navy)
                .statusBarsPadding().navigationBarsPadding()
        ) {
            // Premium header, consistent across all app sections.
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(navy)
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp)
                        .background(gold, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♛", color = navy, fontSize = 29.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("ANB AI", color = gold, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                    Text("YOUR PERSONAL AI ASSISTANT", color = textSub, fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (aiLoaded) "● READY" else "● ROYAL AI", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("v1.8", color = textSub, fontSize = 10.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF244775), thickness = 1.dp)

            when (currentTab) {
                "Home" -> {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = panel),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB98B32))
                            ) {
                                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                    Text("✦  NAMASTE!", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Aapka personal AI.", color = textMain, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        "Main ANB AI hoon. Aap sawaal poochhein, apni purani baaton se context dhoondhein, ya voice se baat karein.",
                                        color = textSub, fontSize = 14.sp, lineHeight = 21.sp
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { currentTab = "Chat" },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = navy),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("✦  Chat now", fontWeight = FontWeight.Bold) }
                                        OutlinedButton(
                                            onClick = { currentTab = "Voice" },
                                            shape = RoundedCornerShape(14.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, gold),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("🎙 Voice", color = gold, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("QUICK ACCESS", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Spacer(Modifier.width(8.dp))
                                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF244775))
                            }
                        }
                        item {
                            val quick = listOf(
                                Triple("◉", "Smart Chat", "Chat"),
                                Triple("♬", "Voice Assistant", "Voice"),
                                Triple("▤", "All Memories", "Chat"),
                                Triple("⌘", "AI Tools", "Tools")
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                quick.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        pair.forEach { item ->
                                            Card(
                                                modifier = Modifier.weight(1f).height(104.dp),
                                                shape = RoundedCornerShape(18.dp),
                                                colors = CardDefaults.cardColors(containerColor = panel),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24518A))
                                            ) {
                                                androidx.compose.foundation.layout.Box(
                                                    modifier = Modifier.fillMaxSize()
                                                        .clickable { currentTab = item.third }
                                                        .padding(12.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                        Text(item.first, color = gold, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                                        Text(item.second, color = textMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = panel),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244775))
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("LOCAL AI ENGINE", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        Spacer(Modifier.weight(1f))
                                        Text(if (modelReady) "● MODEL READY" else "● SETUP REQUIRED", color = if (modelReady) Color(0xFF6DE7B0) else gold, fontSize = 9.sp)
                                    }
                                    Text(if (aiLoaded) "ANB AI is ready on this phone" else if (modelReady) "Model downloaded on this phone" else "Qwen3 0.6B • about 614 MB", color = textMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (aiLoaded) "Local model is running. Open Chat to start asking questions."
                                        else if (modelReady) "Start the local model to use on-device AI."
                                        else "First setup needs internet and free storage. The model is downloaded separately from the APK.",
                                        color = textSub, fontSize = 12.sp, lineHeight = 18.sp
                                    )
                                    if (busy && !modelReady) {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = gold
                                        )
                                        Text("Downloading model: $downloadProgress%", color = textSub, fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = {
                                            if (!busy && !modelReady) {
                                                scope.launch {
                                                    busy = true
                                                    status = "Downloading local model..."
                                                    try {
                                                        repository.downloadModel(context) { downloadProgress = it }
                                                        modelReady = true
                                                        status = "Model downloaded. Tap Start local AI."
                                                    } catch (error: Exception) {
                                                        status = "Download failed: ${error.message ?: "Check internet and retry."}"
                                                    } finally { busy = false }
                                                }
                                            } else if (!busy && modelReady && !aiLoaded) {
                                                scope.launch {
                                                    busy = true
                                                    status = "Loading local model..."
                                                    try {
                                                        repository.start(context)
                                                        aiLoaded = true
                                                        status = "Local AI ready."
                                                        messages = messages + ChatMessage("Local AI ready hai! Ab aap apna sawal poochh sakte hain.", false)
                                                    } catch (error: Exception) {
                                                        status = "Model could not start: ${error.message ?: "This phone may not support this model."}"
                                                    } finally { busy = false }
                                                }
                                            } else if (aiLoaded) currentTab = "Chat"
                                        },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = navy)
                                    ) {
                                        Text(
                                            when {
                                                busy && !modelReady -> "Downloading..."
                                                busy -> "Loading model..."
                                                aiLoaded -> "Open Chat"
                                                modelReady -> "Start local AI"
                                                else -> "Download model • 614 MB"
                                            }, fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(status, color = textSub, fontSize = 10.sp)
                                }
                            }
                        }
                        item {
                            Text("Your chats and memories stay in this app's private local storage. Uninstalling the app may delete them.", color = textSub, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                }
                "Chat" -> {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                            Text("Chat & All Memories", color = textMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("${messages.size} entries", color = textSub, fontSize = 10.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .background(panel, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⌕", color = gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = historySearch,
                                onValueChange = { historySearch = it },
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(color = textMain, fontSize = 13.sp),
                                singleLine = true,
                                decorationBox = { inner ->
                                    Box {
                                        if (historySearch.isEmpty()) {
                                            Text("Search all saved chats...", color = textSub, fontSize = 13.sp)
                                        }
                                        inner()
                                    }
                                }
                            )
                            if (historySearch.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "×",
                                    color = gold,
                                    fontSize = 20.sp,
                                    modifier = Modifier.clickable { historySearch = "" }
                                )
                            }
                        }
                        if (!aiLoaded) {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Start local AI from Home first.", color = textMain, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(5.dp))
                                    Text("Your saved conversation will be restored when you return.", color = textSub, fontSize = 12.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { currentTab = "Home" }, colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = navy)) { Text("Go to Home") }
                                }
                            }
                        }
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            if (visibleMessages.isEmpty()) {
                                item {
                                    Text("No saved messages match this search.", color = textSub, fontSize = 12.sp)
                                }
                            }
                            items(visibleMessages) { message ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Column(
                                        modifier = Modifier.widthIn(max = 310.dp)
                                            .background(
                                                if (message.isUser) Color(0xFF0759C7) else panel,
                                                RoundedCornerShape(18.dp)
                                            )
                                            .then(
                                                if (message.isUser) Modifier else Modifier
                                            )
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        Text(if (message.isUser) "YOU" else "ANB AI", color = if (message.isUser) Color(0xFFDCEBFF) else gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(4.dp))
                                        Text(message.text, color = textMain, fontSize = 14.sp, lineHeight = 21.sp)
                                    }
                                }
                            }
                            if (busy && aiLoaded) item { Text("ANB AI is thinking...", color = textSub, fontSize = 12.sp) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .background(panel, RoundedCornerShape(22.dp))
                                .padding(start = 15.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = input, onValueChange = { input = it },
                                enabled = aiLoaded && !busy,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                textStyle = LocalTextStyle.current.copy(color = textMain, fontSize = 14.sp),
                                singleLine = true,
                                decorationBox = { inner ->
                                    Box {
                                        if (input.isEmpty()) Text("Type ya bolo...", color = textSub, fontSize = 13.sp)
                                        inner()
                                    }
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = { sendMessage() },
                                enabled = aiLoaded && input.isNotBlank() && !busy,
                                shape = CircleShape, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = navy)
                            ) { Text("➤", fontSize = 19.sp) }
                        }
                    }
                }
                "Voice" -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = panel),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB98B32))
                        ) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("VOICE ASSISTANT", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("🎙", color = gold, fontSize = 54.sp)
                                Text("Bol kar baat karein", color = textMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Hindi • Hinglish • device speech services", color = textSub, fontSize = 12.sp)
                            }
                        }
                        VoiceControls(
                            enabled = aiLoaded,
                            latestReply = messages.lastOrNull { !it.isUser && it.text.isNotBlank() }?.text.orEmpty(),
                            onRecognized = { spoken ->
                                when (spoken.trim().lowercase()) {
                                    "send", "send it", "bhej do", "bhejo", "भेज दो", "भेजो" -> sendMessage()
                                    "new chat", "naya chat", "नई चैट", "clear chat", "chat saaf karo" -> {
                                        messages = messages + ChatMessage("— New chat —", false) +
                                            ChatMessage("Namaste! Naya chat shuru karein. Purani chat history saved rahegi.", false)
                                        input = ""
                                        status = "New chat started. Previous history is preserved."
                                    }
                                    else -> {
                                        input = spoken
                                        currentTab = "Chat"
                                        status = "Voice text ready. Send dabayein, ya 'bhej do' bolkar send karein."
                                    }
                                }
                            }
                        )
                        Text("Voice recognition starts only when you tap the voice control; this app does not keep the microphone always listening.", color = textSub, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                "Tools" -> {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Text("AI TOOLS", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                        val tools = listOf(
                            Triple("✦", "Text Chat", "Ask questions and use saved chat memory"),
                            Triple("▧", "Image Generator", "Image generation needs a configured image provider"),
                            Triple("</>", "Code Helper", "Explain code and plan project changes"),
                            Triple("⌕", "Research", "Use available information and verify important facts"),
                            Triple("▤", "Summarize", "Paste text in Chat and ask for a summary"),
                            Triple("文", "Translate", "Ask ANB AI to translate text")
                        )
                        items(tools) { tool ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    input = when (tool.second) {
                                        "Summarize" -> "Is text ka summary banao: "
                                        "Translate" -> "Is text ko Hindi mein translate karo: "
                                        "Code Helper" -> "Mere code ko samjhao: "
                                        "Research" -> "Is topic par research karo: "
                                        else -> ""
                                    }
                                    currentTab = "Chat"
                                },
                                shape = RoundedCornerShape(17.dp),
                                colors = CardDefaults.cardColors(containerColor = panel),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244775))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp).background(panelLight, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                                        Text(tool.first, color = gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tool.second, color = textMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(tool.third, color = textSub, fontSize = 11.sp, lineHeight = 15.sp)
                                    }
                                    Text("›", color = gold, fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
                else -> {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text("SETTINGS", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("Personalize ANB AI", color = textMain, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = panel),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB98B32))
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("APPEARANCE", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Royal Blue & Royal Gold", color = textMain, fontWeight = FontWeight.Bold)
                                            Text("Current signature theme", color = textSub, fontSize = 11.sp)
                                        }
                                        Text("✓ ACTIVE", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = panel)
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    SettingSwitchRow("Sound effects", "App sound preferences", soundOn, { soundOn = it }, gold, textMain, textSub)
                                    HorizontalDivider(color = Color(0xFF244775))
                                    SettingSwitchRow("Vibration", "Haptic feedback preference", vibrationOn, { vibrationOn = it }, gold, textMain, textSub)
                                    HorizontalDivider(color = Color(0xFF244775))
                                    SettingSwitchRow("Animations", "Interface motion preference", animationsOn, { animationsOn = it }, gold, textMain, textSub)
                                    HorizontalDivider(color = Color(0xFF244775))
                                    SettingSwitchRow("Offline-first", "Keep chat history on this phone", offlineOn, { offlineOn = it }, gold, textMain, textSub)
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text("PRIVACY & MEMORY", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text("Saved transcript: ${messages.size} entries", color = textMain, fontWeight = FontWeight.Bold)
                                    Text("The complete conversation is stored in app-private local storage. Uninstalling the app may remove it.", color = textSub, fontSize = 12.sp, lineHeight = 18.sp)
                                    Button(
                                        onClick = { currentTab = "Chat" },
                                        colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = navy),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Open chat history", fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    // ANB_AI_CHAT_EXPORT_V21: share a user-requested plain-text transcript.
                                    OutlinedButton(
                                        onClick = {
                                            val transcript = messages.joinToString("\\n\\n") { message ->
                                                (if (message.isUser) "You" else "ANB AI") + ": " + message.text
                                            }
                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_SUBJECT, "ANB AI chat backup")
                                                putExtra(
                                                    android.content.Intent.EXTRA_TEXT,
                                                    "ANB AI — local chat backup\n\n" + transcript
                                                )
                                            }
                                            context.startActivity(
                                                android.content.Intent.createChooser(shareIntent, "Export ANB AI chat")
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, gold)
                                    ) {
                                        Text("Export / Share Chat Backup", color = gold, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { restoreChatLauncher.launch("text/*") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, gold)
                                    ) {
                                        Text("Restore Chat Backup", color = gold, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "Restoring replaces the current transcript. Choose an ANB AI text backup; multi-line messages may be split.",
                                        color = textSub,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    )
                                    Text(
                                        "Backup is shared only after you choose an app. Review the transcript before sending it anywhere.",
                                        color = textSub,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                        item {
                            Text("ANB AI • Royal Edition 2.0", color = textSub, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }

            // Consistent royal navigation bar.
            HorizontalDivider(color = Color(0xFF244775))
            val navItems = listOf(Triple("Home", "⌂", "Home"), Triple("Chat", "●", "Chat"), Triple("Voice", "♬", "Voice"), Triple("Tools", "⌘", "Tools"), Triple("Settings", "⚙", "Settings"))
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF031027)).padding(horizontal = 4.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                navItems.forEach { item ->
                    val active = currentTab == item.first
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                            .clickable { currentTab = item.first }
                            .background(if (active) Color(0xFF12315B) else Color.Transparent)
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(item.second, color = if (active) gold else textSub, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(item.third, color = if (active) gold else textSub, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    gold: Color,
    textMain: Color,
    textSub: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = textMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = textSub, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04132D),
                checkedTrackColor = gold,
                uncheckedThumbColor = textSub,
                uncheckedTrackColor = Color(0xFF244775)
            )
        )
    }
}

