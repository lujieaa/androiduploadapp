package com.local.photoupload

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object PhotoUploader {
    fun upload(serverUrl: String, saveDir: String, file: File): Result<String> {
        return runCatching {
            val joiner = if (serverUrl.contains("?")) "&" else "?"
            val url = URL(serverUrl + joiner + "dir=" + URLEncoder.encode(saveDir, Charsets.UTF_8.name()))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "image/jpeg")
                setFixedLengthStreamingMode(file.length())
            }
            try {
                connection.outputStream.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) error("HTTP $code $text")
                text
            } finally {
                connection.disconnect()
            }
        }
    }
}
