package com.example.mpesaboard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTPS client for the licensing server. Call off the main thread. */
object Api {
    fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            return status to json
        } finally {
            conn.disconnect()
        }
    }
}
