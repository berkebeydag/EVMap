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

    /**
     * A non-2xx response, carrying the status so callers can tell the cases apart.
     *
     * The status matters here: 429 means wait and retry, 403 from a metered API
     * usually means the allowance is gone and retrying only wastes more of it, and
     * everything else is a plain failure. Parsing that back out of a message string
     * would have been the alternative.
     */
    class HttpException(val code: Int, val detail: String) :
        IllegalStateException("HTTP $code${if (detail.isBlank()) "" else " — $detail"}")

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
                throw HttpException(code, detail)
            }
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }
}
