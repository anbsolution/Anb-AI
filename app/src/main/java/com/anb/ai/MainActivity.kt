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

private val Background = Color(0xFF0B100E)
private val SurfaceDark = Color(0xFF151D19)
private val Green = Color(0xFF52E59A)
private val TextPrimary = Color(0xFFF1F5F2)
private val TextMuted = Color(0xFFA0ADA5)

data class ChatMessage(val text: String, val isUser: Boolean)

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
    }
    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    "Namaste! Main ANB AI hoon. Version 1.1 mein AI aapke phone par run karega. Shuru karne ke liye local model download karein.",
                    false
                )
            )
        )
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
                Text("A", color = Background, fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("ANB AI", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("PERSONAL ASSISTANT", fontSize = 10.sp, color = TextMuted, letterSpacing = 1.5.sp)
            }
            Text(
                if (aiLoaded) "● LOCAL AI" else "● LOCAL MODE",
                color = Green,
                fontSize = 10.sp
            )
        }

        HorizontalDivider(color = Color(0xFF26332B))

        Column(modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp)) {
            Text("Aapka personal AI.", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Private, on-device AI assistant.", color = TextMuted, fontSize = 14.sp)
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

        VoiceControls(
            enabled = aiLoaded,
            latestReply = messages.lastOrNull { !it.isUser && it.text.isNotBlank() }?.text.orEmpty(),
            onRecognized = { spoken ->
                when (spoken.trim().lowercase()) {
                    "new chat", "naya chat", "नई चैट", "clear chat", "chat saaf karo" -> {
                        messages = listOf(
                            ChatMessage("Namaste! Main ANB AI hoon. Naya chat shuru karein.", false)
                        )
                        input = ""
                        status = "New chat ready."
                    }
                    else -> {
                        input = spoken
                        status = "Voice text ready. Send dabakar ANB AI se poochhein."
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
                onClick = {
                    val question = input.trim()
                    if (question.isNotEmpty() && aiLoaded && !busy) {
                        messages = messages + ChatMessage(question, true)
                        input = ""
                        busy = true
                        scope.launch {
                            messages = messages + ChatMessage("", false)
                            try {
                                val answer = repository.ask(question) { partial ->
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
                    }
                },
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
