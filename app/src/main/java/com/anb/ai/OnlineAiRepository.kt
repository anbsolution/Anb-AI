package com.anb.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Calls the Supabase Edge Function. No OpenAI key is ever placed in this Android class. */
class OnlineAiRepository {
    suspend fun ask(accessToken: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) throw IOException("Sign in to Online AI in Settings first.")
        if (prompt.isBlank()) throw IOException("Message is empty.")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt.take(4000)))
        val payload = JSONObject().put("messages", messages)
        val conn = (URL("$PROJECT_URL/functions/v1/anb-ai-chat").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 90_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val response = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (conn.responseCode !in 200..299) {
                val error = response.optString("error").ifBlank { "Online AI request failed (HTTP ${conn.responseCode})." }
                throw IOException(error.take(240))
            }
            response.optString("reply").trim().ifBlank { throw IOException("Online AI returned an empty reply.") }
        } finally { conn.disconnect() }
    }

    companion object {
        const val PROJECT_URL = "https://yisghzbnsmcopczqbrun.supabase.co"
        const val PUBLISHABLE_KEY = "sb_publishable_z6w3dBI7BlCH0hDbmHr_Jg_JsdQpFSk"
    }
}
