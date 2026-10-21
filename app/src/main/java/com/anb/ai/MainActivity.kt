package com.anb.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

    val excerpts = selected.sorted().takeLast(18).map { index ->
        val message = history[index]
        val role = if (message.isUser) "User" else "ANB AI"
        "$role: ${message.text.replace(Regex("\\s+"), " ").take(500)}"
    }
    val contextText = excerpts.joinToString("\n").take(4500)
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
    var modelReady by remember { mutableStateOf(repository.isModelDownloaded(context)) }
    var aiLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var status by remember {
        mutableStateOf(
            if (modelReady) "Model downloaded. Start local AI to begin." else "Local model not downloaded yet."
        )
    }    // ANB_ALL_CHAT_MEMORY_V15: restore the complete locally saved transcript.
    var messages by remember { mutableStateOf(loadAllChatHistory(context)) }

    LaunchedEffect(messages) {
        // Debounce streaming updates so each generated token does not rewrite the whole file.
        kotlinx.coroutines.delay(350)
        saveAllChatHistory(context, messages)
    }

    DisposableEffect(repository) {
        onDispose { repository.closeAsync() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(46.dp).background(Green, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("♛", color = Background, fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("ANB AI", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("ROYAL PERSONAL ASSISTANT", fontSize = 9.sp, color = TextMuted, letterSpacing = 1.1.sp)
            }
            Text(
                if (aiLoaded) "● LOCAL AI" else "● LOCAL MODE",
                color = Green,
                fontSize = 10.sp
            )
        }

        HorizontalDivider(color = Color(0xFF34547C)) // ANB_ROYAL_THEME_V16

        Column(modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp)) {
            Text("Aapka personal AI.", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Royal Blue • Royal Gold • On-device AI", color = TextMuted, fontSize = 13.sp)
        }

        if (!aiLoaded) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LOCAL AI MODEL", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (modelReady) "Model ready on this phone." else "Qwen3 0.6B model • about 614 MB",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (modelReady) "Model file is stored in app-private storage." else "First download needs internet and free storage. The model is not included in the APK.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    if (busy && !modelReady) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Green
                        )
                        Text("Downloading model: $downloadProgress%", color = TextMuted, fontSize = 12.sp)
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
                                    } finally {
                                        busy = false
                                    }
                                }
                            } else if (!busy && modelReady) {
                                scope.launch {
                                    busy = true
                                    status = "Loading local model. First start may take a little while..."
                                    try {
                                        repository.start(context)
                                        aiLoaded = true
                                        status = "Local AI ready. Your prompts run on this device."
                                        messages = messages + ChatMessage("Local AI ready hai! Ab aap apna sawal poochh sakte hain.", false)
                                    } catch (error: Exception) {
                                        status = "Model could not start: ${error.message ?: "This phone may not support this model."}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Background)
                    ) {
                        Text(
                            when {
                                busy && !modelReady -> "Downloading..."
                                busy -> "Loading model..."
                                modelReady -> "Start local AI"
                                else -> "Download model • 614 MB"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(status, color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        // ANB_VOICE_COMMANDS_V14: shared send action for the Send button and voice commands.
        val sendMessage: () -> Unit = {
            val question = input.trim()
            val historyBeforeQuestion = messages
            if (question.isNotEmpty() && aiLoaded && !busy) {
                val prompt = buildMemoryPrompt(question, historyBeforeQuestion)
                messages = messages + ChatMessage(question, true)
                input = ""
                busy = true
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
                            "Local AI error: ${error.message ?: "Please try again."}",
                            false
                        )
                        status = "Local AI request failed. You can retry."
                    } finally {
                        busy = false
                    }
                }
            } else if (question.isBlank()) {
                status = "Pehle apna sawal boliye ya type karein."
            } else if (!aiLoaded) {
                status = "Pehle local AI start karein."
            }
        }

        VoiceControls(
            enabled = aiLoaded,
            latestReply = messages.lastOrNull { !it.isUser && it.text.isNotBlank() }?.text.orEmpty(),
            onRecognized = { spoken ->
                when (spoken.trim().lowercase()) {
                    // ANB_VOICE_COMMANDS_V14: explicit send command; no always-listening mode.
                    "send", "send it", "bhej do", "bhejo", "भेज दो", "भेजो" -> sendMessage()
                    "new chat", "naya chat", "नई चैट", "clear chat", "chat saaf karo" -> {
                        // Keep old messages: preserve all prior chat history.
                        messages = messages + ChatMessage("— New chat —", false) +
                            ChatMessage("Namaste! Naya chat shuru karein. Purani chat history saved rahegi.", false)
                        input = ""
                        status = "New chat started. Previous history is preserved."
                    }
                    else -> {
                        input = spoken
                        status = "Voice text ready. Send dabayein, ya 'bhej do' bolkar send karein."
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Text(
                        text = message.text,
                        color = if (message.isUser) Background else TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.widthIn(max = 310.dp).background(
                            if (message.isUser) Green else SurfaceDark,
                            RoundedCornerShape(18.dp)
                        ).padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            if (busy && aiLoaded) {
                item {
                    Text("ANB AI soch raha hai...", color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                .background(SurfaceDark, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                enabled = aiLoaded && !busy,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 15.sp),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                if (aiLoaded) "ANB AI se poochhein..." else "Pehle local AI start karein...",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { sendMessage() },
                enabled = aiLoaded && input.isNotBlank() && !busy,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Background)
            ) {
                Text("↑", fontSize = 24.sp)
            }
        }

        Text(
            "Local AI can make mistakes. Verify important information.",
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp)
        )
    }
}
