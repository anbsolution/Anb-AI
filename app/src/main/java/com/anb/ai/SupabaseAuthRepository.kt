package com.anb.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Email/password Supabase Auth wrapper. Only public project configuration lives in the APK. */
class SupabaseAuthRepository(context: Context) {
    private val prefs = context.getSharedPreferences("anb_online_session", Context.MODE_PRIVATE)

    fun hasSession(): Boolean = !prefs.getString("refresh_token", null).isNullOrBlank()

    suspend fun signUp(email: String, password: String): String = withContext(Dispatchers.IO) {
        validateCredentials(email, password)
        val response = request("/auth/v1/signup", JSONObject().put("email", email).put("password", password))
        saveSession(response)
        if (hasSession()) "Account created and signed in. Online AI is ready."
        else "Account created. Confirm your email if requested, then sign in."
    }

    suspend fun signIn(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        if (!email.contains("@") || password.isBlank()) throw IOException("Enter a valid email and password.")
        val response = request("/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password))
        if (response.optString("access_token").isBlank() || response.optString("refresh_token").isBlank()) {
            throw IOException("Sign-in did not return a valid session. Confirm your email and try again.")
        }
        saveSession(response)
    }

    suspend fun getValidAccessToken(): String = withContext(Dispatchers.IO) {
        val current = prefs.getString("access_token", null)
        val expiry = prefs.getLong("expires_at", 0L)
        if (!current.isNullOrBlank() && expiry > System.currentTimeMillis() + 60_000L) return@withContext current
        val refresh = prefs.getString("refresh_token", null)
        if (refresh.isNullOrBlank()) throw IOException("Sign in to Online AI in Settings first.")
        try {
            val response = request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh))
            if (response.optString("access_token").isBlank() || response.optString("refresh_token").isBlank()) {
                throw IOException("Session refresh failed. Please sign in again.")
            }
            saveSession(response)
            response.getString("access_token")
        } catch (error: Exception) {
            clearSession()
            throw IOException("Online AI session expired. Sign in again in Settings.", error)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = prefs.getString("access_token", null)
        try {
            if (!token.isNullOrBlank()) {
                val conn = openConnection("/auth/v1/logout", token)
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.outputStream.use { it.write(ByteArray(0)) }
                    conn.inputStreamOrError().close()
                } finally { conn.disconnect() }
            }
        } finally { clearSession() }
    }

    private fun validateCredentials(email: String, password: String) {
        if (!email.contains("@") || email.length > 254) throw IOException("Enter a valid email address.")
        if (password.length < 8 || password.length > 128) throw IOException("Password must be 8–128 characters.")
    }

    private fun saveSession(response: JSONObject) {
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) return
        val expiresIn = response.optLong("expires_in", 3600L).coerceAtLeast(60L)
        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L)
            .apply()
    }

    private fun clearSession() { prefs.edit().clear().apply() }

    private fun request(path: String, body: JSONObject): JSONObject {
        val conn = openConnection(path, null)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val input = conn.inputStreamOrError()
            val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (conn.responseCode !in 200..299) throw IOException(authError(json, conn.responseCode))
            return json
        } finally { conn.disconnect() }
    }

    private fun openConnection(path: String, bearer: String?): HttpURLConnection {
        return (URL("$PROJECT_URL$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
    }

    private fun HttpURLConnection.inputStreamOrError() = if (responseCode in 200..299) inputStream else errorStream ?: inputStream

    private fun authError(json: JSONObject, status: Int): String {
        val message = json.optString("msg").ifBlank {
            json.optString("message").ifBlank {
                json.optString("error_description").ifBlank { json.optString("error") }
            }
        }
        return if (message.isBlank()) "Supabase Auth request failed (HTTP $status)." else message.take(240)
    }

    companion object {
        const val PROJECT_URL = "https://yisghzbnsmcopczqbrun.supabase.co"
        const val PUBLISHABLE_KEY = "sb_publishable_z6w3dBI7BlCH0hDbmHr_Jg_JsdQpFSk"
    }
}
