package io.github.aedev.flow.data.cobalt

import android.util.Log
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.network.ProxyAwareClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-hosted cobalt instance Flow downloads non-YouTube media from. One POST resolves a source
 * URL into a direct file URL; the file itself is fetched by the download transfer, not here.
 */
@Singleton
class CobaltClient
    @Inject
    constructor() {
        private val client: OkHttpClient =
            ProxyAwareClient {
                connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                followRedirects(true)
                retryOnConnectionFailure(true)
            }.get()

        /** Resolves [request] against the configured instance. Never throws: a failure is a result. */
        suspend fun resolve(request: CobaltRequest): CobaltResult =
            withContext(Dispatchers.IO) {
                try {
                    val body = json.encodeToString(CobaltRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
                    val httpRequest =
                        Request
                            .Builder()
                            .url(baseUrl())
                            .header("Accept", "application/json")
                            .post(body)
                            .build()
                    client.newCall(httpRequest).execute().use { response ->
                        val text = response.body.string()
                        if (!response.isSuccessful) {
                            Log.w(TAG, "cobalt answered ${response.code}: ${text.take(200)}")
                            return@withContext CobaltResult.Unavailable("error.api.http.${response.code}")
                        }
                        val parsed =
                            runCatching { json.decodeFromString(CobaltResponse.serializer(), text) }.getOrNull()
                                ?: return@withContext CobaltResult.Unavailable("error.api.unreadable")
                        val url = parsed.url
                        when {
                            parsed.status == STATUS_ERROR || parsed.error != null -> {
                                CobaltResult.Unavailable(parsed.error?.code ?: "error.api.unknown")
                            }

                            url.isNullOrBlank() -> {
                                CobaltResult.Unavailable("error.api.no_url.${parsed.status.ifBlank { "unknown" }}")
                            }

                            else -> {
                                CobaltResult.Ready(url, parsed.filename)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cobalt request failed", e)
                    CobaltResult.Unavailable("error.api.network")
                }
            }

        /**
         * Opens a resolved [url] for reading. A tunnel hands back the whole body and ignores Range,
         * so the caller reads [CobaltStream.Ready.response] once. The response is the caller's to close.
         */
        suspend fun open(url: String): CobaltStream =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("Accept", "*/*")
                            .get()
                            .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        val code = response.code
                        response.close()
                        Log.w(TAG, "tunnel answered $code for ${url.take(120)}")
                        return@withContext CobaltStream.Unavailable("error.api.http.$code")
                    }
                    val length =
                        response.header("Estimated-Content-Length")?.toLongOrNull()
                            ?: response.body.contentLength()
                    CobaltStream.Ready(response, length.coerceAtLeast(0L), response.header("Content-Disposition")?.fileName())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "tunnel request failed", e)
                    CobaltStream.Unavailable("error.api.network")
                }
            }

        /** The configured instance, always ending in a slash so a relative path cannot drop a segment. */
        private fun baseUrl(): String = BuildConfig.COBALT_API_URL.ifBlank { DEFAULT_BASE_URL }

        private companion object {
            const val TAG = "CobaltClient"
            const val DEFAULT_BASE_URL = "https://cobalt-api-yuol.onrender.com/"
            const val STATUS_ERROR = "error"
            const val CONNECT_TIMEOUT_S = 20L
            const val READ_TIMEOUT_S = 60L
            val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
            val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                    explicitNulls = false
                }
        }
    }

/** The `filename="…"` of a Content-Disposition header, when it carries one. */
private fun String.fileName(): String? =
    substringAfter("filename=", "")
        .substringBefore(';')
        .trim()
        .trim('"')
        .takeIf { it.isNotBlank() }
