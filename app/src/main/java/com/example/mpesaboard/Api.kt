package com.example.mpesaboard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Supabase Postgres functions over HTTPS (PostgREST /rpc). Call off the main thread. */
object Api {
    fun rpc(function: String, body: JSONObject): Pair<Int, JSONObject> {
        val key = BuildConfig.SUPABASE_KEY
        val base = BuildConfig.SUPABASE_URL.trim().removeSuffix("/").removeSuffix("/rest/v1")
        val conn = URL("$base/rest/v1/rpc/$function").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", key)
            // Legacy anon keys are JWTs and also go in Authorization; new publishable keys are not.
            if (key.startsWith("eyJ")) conn.setRequestProperty("Authorization", "Bearer $key")
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
