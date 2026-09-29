package com.studentmemory.copilot.services

import com.studentmemory.copilot.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// The Android transport is the only layer aware of HTTP. The native core consumes JSON results.
class BackendClient(private val token: String) {
    // Performs the HTTP call and returns the raw response body plus success flag. The caller
    // parses it as an object or an array depending on the endpoint's contract.
    private fun send(path: String, body: JSONObject?, method: String?): Pair<Boolean, String> {
        val url = URL(BuildConfig.BACKEND_URL.trimEnd('/') + "/api/v1" + path)
        require(url.protocol == "https" || (BuildConfig.DEBUG && url.host == "10.0.2.2")) { "Configure an HTTPS backend URL before using live services."}
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            if (method != null) connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val success = connection.responseCode in 200..299
            val stream = if (success) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return success to response
        } finally { connection.disconnect() }
    }
    fun request(path: String, body: JSONObject? = null, method: String? = null): JSONObject {
        val (success, response) = send(path, body, method)
        val result = JSONObject(response.ifBlank { "{}" })
        check(success) { result.optString("message", "Service unavailable. Please use manual entry.") }
        return result
    }
    // GET an endpoint that returns a top-level JSON array (e.g. /events). Kept separate from
    // request() so array and object contracts stay explicit; HTTP/auth handling is shared.
    fun requestArray(path: String): JSONArray {
        val (success, response) = send(path, null, "GET")
        check(success) {
            val message = try { JSONObject(response.ifBlank { "{}" }).optString("message") } catch (_: Exception) { "" }
            if (message.isNotBlank()) message else "Service unavailable. Please use manual entry."
        }
        return JSONArray(response.ifBlank { "[]" })
    }
    fun delete(path: String) { request(path, method = "DELETE") }
}
