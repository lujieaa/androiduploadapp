package com.local.photoupload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.media.MediaScannerConnection
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class PhotoReceiverService : Service() {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val connections = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (acceptThread?.isAlive == true) return START_STICKY
        serviceStatus = "正在启动接收服务"
        serviceRunning = true
        acceptThread = Thread(::listen, "photo-upload-listener").apply { start() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceRunning = false
        serverSocket?.close()
        connections.shutdownNow()
        serviceStatus = "接收服务已停止"
        super.onDestroy()
    }

    private fun listen() {
        try {
            ServerSocket().use { socket ->
                serverSocket = socket
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", PORT))
                serviceStatus = "接收服务运行中，端口 $PORT"
                while (serviceRunning) {
                    val client = socket.accept()
                    connections.execute { handleClient(client) }
                }
            }
        } catch (error: Exception) {
            if (serviceRunning) {
                serviceStatus = "启动失败：${error.localizedMessage ?: error.javaClass.simpleName}"
                serviceRunning = false
                stopSelf()
            }
        } finally {
            serverSocket = null
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    respond(output, 400, "Bad Request", "请求格式错误")
                    return
                }
                val headers = mutableMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = readLine(input) ?: throw EOFException("请求头不完整")
                    headerBytes += line.length
                    if (headerBytes > MAX_HEADER_BYTES) {
                        respond(output, 431, "Request Header Fields Too Large", "请求头过大")
                        return
                    }
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                            line.substring(separator + 1).trim()
                    }
                }

                val target = Uri.parse(parts[1])
                if (parts[0] == "GET" && target.path == "/health") {
                    respond(output, 200, "OK", """{"ok":true,"save_dir":"Pictures/拍照上传"}""")
                    return
                }
                if (parts[0] != "POST" || target.path != "/upload") {
                    respond(output, 404, "Not Found", "接口不存在")
                    return
                }
                val length = headers["content-length"]?.toLongOrNull()
                if (length == null || length <= 0 || length > MAX_UPLOAD_BYTES) {
                    respond(output, 413, "Payload Too Large", "照片大小不合法")
                    return
                }
                val rawDirectory = target.encodedQuery.orEmpty()
                    .split('&')
                    .firstOrNull { Uri.decode(it.substringBefore('=')) == "dir" }
                    ?.substringAfter('=', "")
                    .orEmpty()
                val directory = URLDecoder.decode(rawDirectory, Charsets.UTF_8.name())
                val saved = savePhoto(input, length, directory)
                respond(output, 200, "OK", """{"ok":true,"saved":"$saved","name":"${saved.substringAfterLast('/')}"}""")
            } catch (error: Exception) {
                val message = error.localizedMessage ?: "上传失败"
                runCatching { respond(output, 400, "Bad Request", message) }
            }
        }
    }

    private fun savePhoto(input: BufferedInputStream, length: Long, requestedDir: String): String {
        val prefix = ByteArray(3)
        var prefixRead = 0
        while (prefixRead < prefix.size) {
            val count = input.read(prefix, prefixRead, prefix.size - prefixRead)
            if (count < 0) throw EOFException("照片数据不完整")
            prefixRead += count
        }
        if (prefix[0] != 0xff.toByte() || prefix[1] != 0xd8.toByte() || prefix[2] != 0xff.toByte()) {
            throw IllegalArgumentException("只接收 JPEG")
        }

        val directory = safeDirectory(requestedDir)
        val relativePath = "Pictures/$ALBUM_NAME" + if (directory.isEmpty()) "" else "/$directory"
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val imageUri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建相册照片")
            try {
                contentResolver.openOutputStream(imageUri)?.use { output ->
                    writePhoto(input, length, prefix, output)
                } ?: throw IllegalStateException("无法写入相册照片")
                contentResolver.update(imageUri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            } catch (error: Exception) {
                contentResolver.delete(imageUri, null, null)
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), relativePath.removePrefix("Pictures/"))
            if (!album.exists() && !album.mkdirs()) throw IllegalStateException("无法创建相册目录")
            val image = File(album, name)
            try {
                image.outputStream().use { output -> writePhoto(input, length, prefix, output) }
            } catch (error: Exception) {
                image.delete()
                throw error
            }
            MediaScannerConnection.scanFile(this, arrayOf(image.absolutePath), arrayOf("image/jpeg"), null)
        }
        return "$relativePath/$name"
    }

    private fun writePhoto(input: BufferedInputStream, length: Long, prefix: ByteArray, output: OutputStream) {
        output.write(prefix)
        var remaining = length - prefix.size
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("照片数据不完整")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun safeDirectory(value: String): String {
        val decoded = value.replace('\\', '/')
        val parts = decoded.split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "目录不能包含 .." }
        return parts.joinToString("/") { part ->
            part.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.' }
                .trim()
                .take(MAX_DIRECTORY_NAME)
                .ifEmpty { "其他" }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.ISO_8859_1)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        if (bytes.size > MAX_LINE_BYTES) throw IllegalArgumentException("请求行过长")
        return String(bytes.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun respond(output: BufferedOutputStream, code: Int, reason: String, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code $reason\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "照片接收服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("拍照上传接收端")
            .setContentText("正在监听端口 $PORT")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        const val PORT = 8080
        private const val NOTIFICATION_ID = 1701
        private const val CHANNEL_ID = "photo_receiver"
        private const val ALBUM_NAME = "拍照上传"
        private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_DIRECTORY_NAME = 80
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000

        @Volatile
        var serviceRunning = false
            private set

        @Volatile
        var serviceStatus = "接收服务已停止"
            private set
    }
}