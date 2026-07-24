package com.berke.ioniqscope.charging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP helper.
 *
 * HttpURLConnection rather than OkHttp/Retrofit: two GETs and one POST do not
 * justify a networking stack, and the project has kept its dependency list to
 * things it genuinely needs.
 */
internal object Http {

    /** Identifies the app to tile and API operators, as their usage policies ask. */
    const val USER_AGENT = "IoniqScope/0.1 (personal OBD app; contact via GitHub)"

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 120_000

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url, method = "GET", body = null, headers = headers)

    suspend fun postForm(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String = request(
        url,
        method = "POST",
        body = body,
        headers = headers + ("Content-Type" to "application/x-www-form-urlencoded")
    )

    private suspend fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use(BufferedReader::readText)
                    ?.take(300)
                    .orEmpty()
                throw IllegalStateException("HTTP $code${if (detail.isBlank()) "" else " — $detail"}")
            }
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }
}
