package com.local.photoupload

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class ServerActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var addressesText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startReceiverService() }
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionOrStart()
        else Toast.makeText(this, "需要存储权限才能写入系统相册", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = "平板接收端"
            textSize = 26f
        }
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        addressesText = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        val note = TextView(this).apply {
            text = "拍照端在设置中填写对应的上传地址。局域网连接同一 Wi-Fi；远程连接时，平板和手机都需登录同一 Tailscale 网络。照片会出现在系统相册的“拍照上传”目录。"
            textSize = 14f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val startButton = Button(this).apply {
            text = "启动接收服务"
            setOnClickListener { requestAndStartService() }
        }
        val stopButton = Button(this).apply {
            text = "停止接收服务"
            setOnClickListener { stopService(Intent(this@ServerActivity, PhotoReceiverService::class.java)) }
        }
        val copyButton = Button(this).apply {
            text = "复制首个上传地址"
            setOnClickListener { copyFirstAddress() }
        }
        val backButton = Button(this).apply {
            text = "返回拍照"
            setOnClickListener { finish() }
        }
        content.addView(title)
        content.addView(statusText)
        content.addView(addressesText)
        content.addView(note)
        content.addView(startButton)
        content.addView(stopButton)
        content.addView(copyButton)
        content.addView(backButton)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        updateAddresses()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun requestAndStartService() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        requestNotificationPermissionOrStart()
    }

    private fun requestNotificationPermissionOrStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val intent = Intent(this, PhotoReceiverService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = PhotoReceiverService.serviceStatus
    }

    private fun updateAddresses() {
        val addresses = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network -> Collections.list(network.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { it.hostAddress }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        addressesText.text = if (addresses.isEmpty()) {
            "未检测到网络地址。连接 Wi-Fi 或 Tailscale 后刷新此页面。\n\n上传地址格式：\nhttp://平板IP:${PhotoReceiverService.PORT}/upload"
        } else {
            "本机可用地址：\n" + addresses.joinToString("\n") {
                "http://$it:${PhotoReceiverService.PORT}/upload"
            }
        }
    }

    private fun copyFirstAddress() {
        val address = addressesText.text.toString()
            .lineSequence()
            .firstOrNull { it.startsWith("http://") }
        if (address == null) {
            Toast.makeText(this, "没有可复制的网络地址", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("照片上传地址", address))
        Toast.makeText(this, "已复制 $address", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}