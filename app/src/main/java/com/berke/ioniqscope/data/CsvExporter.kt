package com.berke.ioniqscope.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a trip to a user-chosen file via SAF.
 *
 * Samples are stored one row per reading; here they are pivoted back into one CSV
 * row per poll snapshot. Rows are streamed a page at a time so a multi-hour trip
 * never has to fit in memory.
 */
class CsvExporter(
    private val context: Context,
    private val tripDao: TripDao
) {

    /** @return number of data rows written. */
    suspend fun exportTrip(tripId: Long, destination: Uri): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trip = tripDao.trip(tripId)
                    ?: throw IllegalArgumentException("Trip $tripId no longer exists")
                val columns = tripDao.pidColumns(tripId)

                val stream = context.contentResolver.openOutputStream(destination, "wt")
                    ?: throw IllegalStateException("Could not open the selected file for writing")

                stream.bufferedWriter().use { out ->
                    writeHeader(out, columns)
                    streamRows(out, tripId, trip.startedAtEpochMs, columns)
                }
            }
        }

    private fun writeHeader(out: BufferedWriter, columns: List<PidColumn>) {
        val header = buildList {
            add("timestamp")
            add("elapsed_ms")
            columns.forEach { add("${it.label} (${it.unit})") }
        }
        out.write(header.joinToString(",") { escape(it) })
        out.newLine()
    }

    private suspend fun streamRows(
        out: BufferedWriter,
        tripId: Long,
        startedAt: Long,
        columns: List<PidColumn>
    ): Int {
        var offset = 0
        var written = 0

        var pendingAt: Long? = null
        val pending = mutableMapOf<String, Double>()

        fun flush() {
            val at = pendingAt ?: return
            val cells = buildList {
                add(isoFormatter.format(Instant.ofEpochMilli(at)))
                add((at - startedAt).toString())
                columns.forEach { col ->
                    add(pending[col.pidKey]?.let { formatValue(it) } ?: "")
                }
            }
            out.write(cells.joinToString(",") { escape(it) })
            out.newLine()
            written++
            pending.clear()
        }

        while (true) {
            val page = tripDao.samplesPage(tripId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (sample in page) {
                if (pendingAt != null && sample.atEpochMs != pendingAt) flush()
                pendingAt = sample.atEpochMs
                pending[sample.pidKey] = sample.value
            }
            offset += page.size
            if (page.size < PAGE_SIZE) break
        }
        flush()
        return written
    }

    /** Fixed 3-decimal output — locale-independent so the CSV parses anywhere. */
    private fun formatValue(v: Double): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field

    private companion object {
        const val PAGE_SIZE = 2000
        val isoFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
    }
}

/** Default filename offered to the system file picker. */
fun defaultTripFileName(startedAtEpochMs: Long): String {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(startedAtEpochMs))
    return "ioniqscope-trip-$stamp.csv"
}
