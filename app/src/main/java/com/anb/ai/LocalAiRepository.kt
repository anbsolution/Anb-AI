package com.anb.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Runs a LiteRT-LM model stored in the app's private files directory. */
class LocalAiRepository {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val mutex = Mutex()

    fun isModelDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_MODEL_BYTES
    }

    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val target = modelFile(context)
            if (target.isFile && target.length() >= MIN_MODEL_BYTES) {
                withContext(Dispatchers.Main) { onProgress(100) }
                return@withContext
            }

            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.part")
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }

            try {
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Model server returned HTTP ${connection.responseCode}")
                }

                val expectedLength = connection.contentLengthLong
                var downloaded = 0L
                var lastReported = -1
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (expectedLength > 0L) {
                                val percent = ((downloaded * 100L) / expectedLength).toInt().coerceIn(0, 100)
                                if (percent != lastReported) {
                                    lastReported = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (temporary.length() < MIN_MODEL_BYTES ||
                    (expectedLength > 0L && temporary.length() != expectedLength)
                ) {
                    throw IOException("Downloaded model is incomplete. Please retry the download.")
                }

                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                withContext(Dispatchers.Main) { onProgress(100) }
            } catch (error: Exception) {
                temporary.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun start(context: Context) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (conversation != null) return@withLock
                val model = modelFile(context)
                if (!model.isFile || model.length() < MIN_MODEL_BYTES) {
                    throw IOException("Local model is not downloaded yet.")
                }

                val newEngine = Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                )
                try {
                    newEngine.initialize()
                    val newConversation = newEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "You are ANB AI, a helpful personal assistant. Reply clearly and naturally. " +
                                    "Use Hindi or Hinglish when the user writes in Hindi or Hinglish. " +
                                    "Be honest when you do not know something. Keep answers concise unless asked for detail."
                            ),
                            samplerConfig = SamplerConfig(
                                topK = 20,
                                topP = 0.85,
                                temperature = 0.6
                            )
                        )
                    )
                    engine = newEngine
                    conversation = newConversation
                } catch (error: Exception) {
                    runCatching { newEngine.close() }
                    throw error
                }
            }
        }
    }

    suspend fun ask(question: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val activeConversation = conversation
                ?: throw IllegalStateException("Local AI is not started yet.")
            val answer = StringBuilder()
            activeConversation.sendMessageAsync(question).collect { partialMessage ->
                answer.append(partialMessage.text)
            }
            answer.toString().trim().ifBlank {
                "Model ne koi text response nahi diya. Dobara try karein."
            }
        }
    }

    fun closeAsync() {
        Thread {
            synchronized(this) {
                runCatching { conversation?.close() }
                conversation = null
                runCatching { engine?.close() }
                engine = null
            }
        }.apply { name = "anb-ai-model-close" }.start()
    }

    private fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILE_NAME)

    companion object {
        const val MODEL_FILE_NAME = "Qwen3-0.6B.litertlm"
        const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
        private const val MIN_MODEL_BYTES = 300_000_000L
    }
}
