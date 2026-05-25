package com.example.falldetector

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider

object ArduinoDataLogger {

    private const val LOG_DIR_NAME = "FallDetectorLogs"

    private const val RAW_JSONL_FILE = "arduino_messages_raw.jsonl"
    private const val ALL_MESSAGES_CSV_FILE = "arduino_messages.csv"
    private const val DEBUG_CSV_FILE = "arduino_debug.csv"
    private const val EVENT_SAMPLES_CSV_FILE = "arduino_event_samples.csv"
    private const val APP_EVENTS_CSV_FILE = "app_events.csv"

    private val executor = Executors.newSingleThreadExecutor()

    private val debugTokenRegex = Regex("""([A-Za-z_][A-Za-z0-9_]*)=([^ ]+)""")

    fun logIncomingMessage(context: Context, rawText: String) {
        val appContext = context.applicationContext

        executor.execute {
            try {
                logIncomingMessageBlocking(appContext, rawText)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun logAppEvent(context: Context, event: String, detail: String = "") {
        val appContext = context.applicationContext

        executor.execute {
            try {
                val nowMs = System.currentTimeMillis()
                val nowIso = isoNow()

                val file = File(logDir(appContext), APP_EVENTS_CSV_FILE)

                appendCsvLine(
                    file = file,
                    header = "phone_time_ms,phone_time_iso,event,detail",
                    values = listOf(
                        nowMs.toString(),
                        nowIso,
                        event,
                        detail
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getLogDirectory(context: Context): File {
        return logDir(context.applicationContext)
    }

    fun getLogFiles(context: Context): List<File> {
        val dir = logDir(context.applicationContext)

        return listOf(
            File(dir, RAW_JSONL_FILE),
            File(dir, ALL_MESSAGES_CSV_FILE),
            File(dir, DEBUG_CSV_FILE),
            File(dir, EVENT_SAMPLES_CSV_FILE),
            File(dir, APP_EVENTS_CSV_FILE)
        ).filter { it.exists() && it.length() > 0 }
    }

    private fun logIncomingMessageBlocking(context: Context, rawText: String) {
        val nowMs = System.currentTimeMillis()
        val nowIso = isoNow()

        val json = try {
            JSONObject(rawText)
        } catch (_: Exception) {
            null
        }

        val type = json?.optString("type")?.takeIf { it.isNotBlank() } ?: "non_json"
        val message = json?.optString("message") ?: rawText

        logRawJsonl(context, nowMs, nowIso, type, rawText)
        logAllMessagesCsv(context, nowMs, nowIso, type, message, rawText)

        if (type.equals("debug", ignoreCase = true)) {
            logDebugCsv(context, nowMs, nowIso, message, rawText)
        }

        if (type.equals("event_window", ignoreCase = true)) {
            logEventWindowCsv(context, nowMs, nowIso, json, rawText)
        }

        if (
            type.equals("fall", ignoreCase = true) ||
            type.equals("event", ignoreCase = true) ||
            type.equals("status", ignoreCase = true)
        ) {
            logAppRelevantArduinoEvent(context, nowMs, nowIso, type, message)
        }
    }

    private fun logRawJsonl(
        context: Context,
        nowMs: Long,
        nowIso: String,
        type: String,
        rawText: String
    ) {
        val file = File(logDir(context), RAW_JSONL_FILE)

        val record = JSONObject()
            .put("phone_time_ms", nowMs)
            .put("phone_time_iso", nowIso)
            .put("type", type)
            .put("raw", rawText)

        file.appendText(record.toString() + "\n")
    }

    private fun logAllMessagesCsv(
        context: Context,
        nowMs: Long,
        nowIso: String,
        type: String,
        message: String,
        rawText: String
    ) {
        val file = File(logDir(context), ALL_MESSAGES_CSV_FILE)

        appendCsvLine(
            file = file,
            header = "phone_time_ms,phone_time_iso,type,message,raw_json",
            values = listOf(
                nowMs.toString(),
                nowIso,
                type,
                message,
                rawText
            )
        )
    }

    private fun logDebugCsv(
        context: Context,
        nowMs: Long,
        nowIso: String,
        message: String,
        rawText: String
    ) {
        val parsed = parseDebugMessage(message)

        val calParts = parsed["cal"]
            ?.split("/")
            ?.map { it.trim() }
            ?: emptyList()

        val acc = parsed["acc"].orEmpty()
        val gyro = parsed["gyro"].orEmpty()
        val energy = parsed["energy"]
            ?: parsed["totalEnergy"]
            ?: parsed["total_energy"]
            ?: ""

        val posture = parsed["posture"]
            ?: parsed["postureAngle"]
            ?: parsed["posture_angle"]
            ?: ""

        val lying = parsed["lying"].orEmpty()
        val inactive = parsed["inactive"].orEmpty()
        val candidate = parsed["candidate"]
            ?: parsed["possibleFall"]
            ?: parsed["possible_fall"]
            ?: ""

        val calSys = calParts.getOrNull(0).orEmpty()
        val calGyro = calParts.getOrNull(1).orEmpty()
        val calAccel = calParts.getOrNull(2).orEmpty()
        val calMag = calParts.getOrNull(3).orEmpty()

        val file = File(logDir(context), DEBUG_CSV_FILE)

        appendCsvLine(
            file = file,
            header = "phone_time_ms,phone_time_iso,acc,gyro,energy,posture,lying,inactive,candidate,cal_sys,cal_gyro,cal_accel,cal_mag,debug_message,raw_json",
            values = listOf(
                nowMs.toString(),
                nowIso,
                acc,
                gyro,
                energy,
                posture,
                lying,
                inactive,
                candidate,
                calSys,
                calGyro,
                calAccel,
                calMag,
                message,
                rawText
            )
        )
    }

    private fun logEventWindowCsv(
        context: Context,
        nowMs: Long,
        nowIso: String,
        json: JSONObject?,
        rawText: String
    ) {
        if (json == null) return

        val samples = findSamplesArray(json) ?: return

        val eventId = json.optString("event_id")
            .takeIf { it.isNotBlank() }
            ?: json.optString("eventId").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        val file = File(logDir(context), EVENT_SAMPLES_CSV_FILE)

        for (i in 0 until samples.length()) {
            val sample = samples.optJSONObject(i)

            if (sample == null) {
                appendCsvLine(
                    file = file,
                    header = "event_id,phone_time_ms,phone_time_iso,sample_index,t_ms,acc_mag,gyro_mag,fall_energy,total_energy,pitch,roll,posture,lying,inactive,raw_sample_json,raw_event_json",
                    values = listOf(
                        eventId,
                        nowMs.toString(),
                        nowIso,
                        i.toString(),
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        samples.optString(i),
                        rawText
                    )
                )
                continue
            }

            appendCsvLine(
                file = file,
                header = "event_id,phone_time_ms,phone_time_iso,sample_index,t_ms,acc_mag,gyro_mag,fall_energy,total_energy,pitch,roll,posture,lying,inactive,raw_sample_json,raw_event_json",
                values = listOf(
                    eventId,
                    nowMs.toString(),
                    nowIso,
                    optAny(sample, "sample_index", "index", "i").ifBlank { i.toString() },
                    optAny(sample, "t_ms", "timestampMs", "timeMs", "nowMs", "ms", "t"),
                    optAny(sample, "accMag", "acc_mag", "acc"),
                    optAny(sample, "gyroMag", "gyro_mag", "gyro"),
                    optAny(sample, "fallEnergy", "fall_energy"),
                    optAny(sample, "totalEnergy", "total_energy", "energy"),
                    optAny(sample, "pitch"),
                    optAny(sample, "roll"),
                    optAny(sample, "posture", "postureAngle", "posture_angle"),
                    optAny(sample, "lying", "lyingDown"),
                    optAny(sample, "inactive"),
                    sample.toString(),
                    rawText
                )
            )
        }
    }

    private fun logAppRelevantArduinoEvent(
        context: Context,
        nowMs: Long,
        nowIso: String,
        type: String,
        message: String
    ) {
        val file = File(logDir(context), APP_EVENTS_CSV_FILE)

        appendCsvLine(
            file = file,
            header = "phone_time_ms,phone_time_iso,event,detail",
            values = listOf(
                nowMs.toString(),
                nowIso,
                "arduino_$type",
                message
            )
        )
    }

    private fun parseDebugMessage(message: String): Map<String, String> {
        return debugTokenRegex
            .findAll(message)
            .associate { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                key to value
            }
    }

    private fun findSamplesArray(json: JSONObject): JSONArray? {
        json.optJSONArray("samples")?.let { return it }

        json.optJSONObject("data")
            ?.optJSONArray("samples")
            ?.let { return it }

        val message = json.optString("message", "").trim()

        if (message.startsWith("[")) {
            return try {
                JSONArray(message)
            } catch (_: Exception) {
                null
            }
        }

        if (message.startsWith("{")) {
            return try {
                JSONObject(message).optJSONArray("samples")
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun optAny(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (json.has(key) && !json.isNull(key)) {
                return json.optString(key)
            }
        }

        return ""
    }

    private fun appendCsvLine(
        file: File,
        header: String,
        values: List<String>
    ) {
        file.parentFile?.mkdirs()

        if (!file.exists() || file.length() == 0L) {
            file.appendText(header + "\n")
        }

        file.appendText(values.joinToString(",") { csvEscape(it) } + "\n")
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun logDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir

        return File(baseDir, LOG_DIR_NAME).apply {
            mkdirs()
        }
    }

    private fun isoNow(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        formatter.timeZone = TimeZone.getDefault()
        return formatter.format(Date())
    }

    fun shareLogFiles(context: Context) {
        val files = getLogFiles(context)

        if (files.isEmpty()) {
            Toast.makeText(context, "No logs yet", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList(
            files.map { file ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }
        )

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "Share fall detector logs")
        )
    }
}