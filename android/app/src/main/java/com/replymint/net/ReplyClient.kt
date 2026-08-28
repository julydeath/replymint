package com.replymint.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The backend rejected our token (HTTP 401) — the user must sign in again. */
class AuthRequiredException : IOException("Signed out")

/** The user hit the free-tier daily cap (HTTP 429). */
class DailyLimitException : IOException("Daily limit reached")

/**
 * Thin OkHttp client to the backend. No Retrofit/Moshi — one endpoint, hand-rolled, lightweight.
 * 60s call timeout: the free-tier backend spins down when idle and takes ~50s to cold-start.
 */
class ReplyClient(
    private val baseUrl: String,
    private val token: String?
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestReply(req: ReplyRequest): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(ReplyRequest.serializer(), req)
                .toRequestBody(JSON_MEDIA)

            if (token == null) throw AuthRequiredException()
            val request = Request.Builder()
                .url("$baseUrl/v1/reply")
                .addHeader("Authorization", "Bearer $token")
                .post(payload)
                .build()

            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> throw AuthRequiredException()
                    resp.code == 429 -> throw DailyLimitException()
                    !resp.isSuccessful -> throw IOException("HTTP ${resp.code}: ${body.take(200)}")
                }
                json.decodeFromString(ReplyResponse.serializer(), body).draft
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
