package com.risktracker.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Apps Script web app in sheet-sync/Code.gs. Call from a background thread. */
object SyncClient {
    data class Result(val entries: List<PnlEntry>, val inr: Double)

    fun sync(url: String, token: String, push: List<PnlEntry>): Result {
        if (!url.startsWith("https://script.google.com/")) {
            throw IOException("URL must start with https://script.google.com/")
        }
        val entries = JSONArray()
        push.forEach {
            entries.put(JSONObject().put("date", it.date).put("balance", it.balance).put("pnl", it.pnl))
        }
        val body = JSONObject()
            .put("token", token)
            .put("action", if (push.isEmpty()) "pull" else "push")
            .put("entries", entries)

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.instanceFollowRedirects = true // Apps Script answers with a redirect to the result
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Unexpected reply. Check the script is deployed as a Web app with access set to Anyone.")
            }
            if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Sync failed"))

            val arr = json.getJSONArray("entries")
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PnlEntry(o.getString("date"), o.getDouble("balance"), o.getDouble("pnl"))
            }
            val inr = json.optDouble("inr", 0.0).takeIf { it > 0 } ?: inrRate
            return Result(list, inr)
        } finally {
            conn.disconnect()
        }
    }
}
