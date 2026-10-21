package com.anb.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

@Composable
fun VoiceControls(
    enabled: Boolean,
    latestReply: String,
    onRecognized: (String) -> Unit
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var voiceReplies by remember { mutableStateOf(true) }
    var ttsReady by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("Hindi/Hinglish voice ready") }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    val latestReplyState = rememberUpdatedState(latestReply)
    val recognizedCallback = rememberUpdatedState(onRecognized)

    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    DisposableEffect(recognizer) {
        if (recognizer == null) {
            voiceStatus = "Is phone par speech recognition available nahi hai."
        } else {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    voiceStatus = "Suniye… ab boliye."
                }
                override fun onBeginningOfSpeech() {
                    listening = true
                    voiceStatus = "Aapki awaaz sun raha hoon…"
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    listening = false
                    voiceStatus = "Awaaz process ho rahi hai…"
                }
                override fun onError(error: Int) {
                    listening = false
                    voiceStatus = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission zaroori hai."
                        SpeechRecognizer.ERROR_NO_MATCH -> "Awaaz samajh nahi aayi. Dobara try karein."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition ke liye network check karein."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy hai. Thodi der baad try karein."
                        else -> "Voice input nahi chala. Dobara try karein."
                    }
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (spoken.isBlank()) {
                        voiceStatus = "Koi text nahi mila. Dobara try karein."
                        return
                    }
                    when (spoken.lowercase(Locale.ROOT).replace(Regex("[.!?,]"), "").trim()) {
                        "stop speaking", "stop voice", "awaaz band karo", "bolna band karo" -> {
                            tts?.stop()
                            voiceStatus = "Voice reply rok diya."
                        }
                        "repeat answer", "repeat reply", "dobara sunao", "jawab dobara sunao" -> {
                            val reply = latestReplyState.value
                            if (reply.isNotBlank() && ttsReady) {
                                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "anb-ai-repeat")
                                voiceStatus = "Latest jawab dobara suna raha hoon."
                            } else {
                                voiceStatus = "Abhi repeat karne ke liye jawab nahi hai."
                            }
                        }
                        else -> {
                            recognizedCallback.value(spoken)
                            voiceStatus = "Voice text chat box mein aa gaya. Send dabayein."
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }

    DisposableEffect(context) {
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val hindiResult = created?.setLanguage(Locale("hi", "IN"))
                if (hindiResult == TextToSpeech.LANG_MISSING_DATA ||
                    hindiResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    created?.language = Locale.US
                }
                ttsReady = true
            } else {
                voiceStatus = "Text-to-speech is phone par available nahi hai."
            }
        }
        tts = created
        onDispose {
            created?.stop()
            created?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    LaunchedEffect(latestReply, voiceReplies, enabled, ttsReady) {
        if (enabled && voiceReplies && ttsReady && latestReply.isNotBlank()) {
            tts?.speak(latestReply, TextToSpeech.QUEUE_FLUSH, null, "anb-ai-reply")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecognition(recognizer, context) { listening = it }
        } else {
            voiceStatus = "Voice input ke liye microphone permission allow karein."
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                if (!enabled) {
                    voiceStatus = "Pehle local AI start karein."
                } else if (recognizer == null) {
                    voiceStatus = "Speech recognition is device par available nahi hai."
                } else if (listening) {
                    recognizer.cancel()
                    listening = false
                    voiceStatus = "Listening rok di."
                } else if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startRecognition(recognizer, context) { listening = it }
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (listening) "■ Stop listening" else "🎙 Bolkar poochhein")
        }
        Button(
            onClick = {
                voiceReplies = !voiceReplies
                if (!voiceReplies) tts?.stop()
                voiceStatus = if (voiceReplies) "Voice replies ON" else "Voice replies OFF"
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (voiceReplies) "🔊 Voice ON" else "🔇 Voice OFF")
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(voiceStatus, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            tts?.stop()
            voiceStatus = "Voice reply rok diya."
        }) { Text("Stop voice") }
    }
}

private fun startRecognition(
    recognizer: SpeechRecognizer?,
    context: android.content.Context,
    onListeningChanged: (Boolean) -> Unit
) {
    if (recognizer == null) return
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Hindi ya Hinglish mein boliye")
    }
    try {
        recognizer.startListening(intent)
        onListeningChanged(true)
    } catch (_: Exception) {
        onListeningChanged(false)
    }
}
