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

/**
 * Auth + account endpoints. Same hand-rolled OkHttp style as [ReplyClient].
 * The long timeout absorbs the free-tier backend's cold start (~50s spin-up).
 */
class AuthClient(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Trades a Google ID token for our opaque session token. */
    suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthExchangeResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = json
                    .encodeToString(AuthExchangeRequest.serializer(), AuthExchangeRequest(idToken))
                    .toRequestBody(JSON_MEDIA)
                val request = Request.Builder()
                    .url("$baseUrl/v1/auth/google")
                    .post(payload)
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(200)}")
                    json.decodeFromString(AuthExchangeResponse.serializer(), body)
                }
            }
        }

    /** Usage + identity for the home screen. Doubles as a backend warm-up ping. */
    suspend fun fetchMe(token: String): Result<MeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/v1/me")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 401) throw AuthRequiredException()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(200)}")
                json.decodeFromString(MeResponse.serializer(), body)
            }
        }
    }

    /** Best-effort server-side revocation; local state is cleared regardless. */
    suspend fun signOut(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/v1/auth/signout")
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(request).execute().use { }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
