package com.local.photoupload

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.local.photoupload.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isBackCamera = true
    private val captureInFlight = AtomicBoolean(false)
    private var lastPhoto: File? = null
    private var serverUrl = ""
    private var saveDirectory = ""

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true ||
            hasCameraPermission()
        ) {
            startCamera()
            if (!hasMediaReadPermission()) {
                setStatus(getString(R.string.status_no_media_permission))
            }
        } else {
            setStatus(getString(R.string.status_no_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val (configuredServer, dir) = AppConfig.load(this)
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: configuredServer
        saveDirectory = dir
        if (serverUrl != configuredServer) {
            AppConfig.save(this, serverUrl, saveDirectory)
        }
        binding.retakeButton.setOnClickListener { takePhoto() }
        binding.retryUploadButton.setOnClickListener { uploadLast() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.settingsButton.setOnClickListener { showSettings() }
        setupFloatingButtons()

        if (hasCameraPermission() && hasMediaReadPermission()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val server = intent.getStringExtra(EXTRA_SERVER_URL)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        serverUrl = server
        AppConfig.save(this, serverUrl, saveDirectory)
    }

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasMediaReadPermission(): Boolean {
        val permissions = mediaReadPermissions()
        return permissions.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mediaReadPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requiredPermissions(): Array<String> =
        arrayOf(Manifest.permission.CAMERA) + mediaReadPermissions()

    private fun setupFloatingButtons() {
        val root = binding.root
        root.post {
            val mainButton = binding.retakeButton
            val retryButton = binding.retryUploadButton
            val maxTop = (binding.bottomPanel.top - mainButton.height - dp(12)).coerceAtLeast(0)
            val mainTop = maxTop
            val retryTop = (mainTop - retryButton.height - dp(8)).coerceAtLeast(0)
            restoreButtonPosition(
                mainButton,
                "capture",
                (root.width - mainButton.width) / 2,
                mainTop
            )
            restoreButtonPosition(
                retryButton,
                "retry",
                (root.width - retryButton.width) / 2,
                retryTop
            )
        }
        setupDraggableButton(binding.retakeButton, "capture")
        setupDraggableButton(binding.retryUploadButton, "retry")
    }

    private fun restoreButtonPosition(button: Button, key: String, defaultLeft: Int, defaultTop: Int) {
        val root = binding.root
        val prefs = getSharedPreferences(PREF_FLOATING_BUTTONS, MODE_PRIVATE)
        val maxLeft = (root.width - button.width).coerceAtLeast(0)
        val maxTop = (root.height - button.height).coerceAtLeast(0)
        val left = if (prefs.contains("$key.x")) {
            (prefs.getFloat("$key.x", 0f) * maxLeft).roundToInt()
        } else {
            defaultLeft
        }
        val top = if (prefs.contains("$key.y")) {
            (prefs.getFloat("$key.y", 0f) * maxTop).roundToInt()
        } else {
            defaultTop
        }
        setButtonPosition(button, left, top)
    }

    private fun setupDraggableButton(button: Button, key: String) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false
        val startLongPress = Runnable {
            dragging = true
            button.isPressed = false
        }

        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = button.layoutParams as FrameLayout.LayoutParams
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                    dragging = false
                    button.isPressed = true
                    mainHandler.postDelayed(
                        startLongPress,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (abs(event.rawX - downRawX) > touchSlop ||
                            abs(event.rawY - downRawY) > touchSlop)
                    ) {
                        mainHandler.removeCallbacks(startLongPress)
                    }
                    if (dragging) {
                        val left = startLeft + (event.rawX - downRawX).roundToInt()
                        val top = startTop + (event.rawY - downRawY).roundToInt()
                        setButtonPosition(button, left, top)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(startLongPress)
                    button.isPressed = false
                    if (dragging) {
                        saveButtonPosition(button, key)
                    } else {
                        button.performClick()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(startLongPress)
                    button.isPressed = false
                    dragging = false
                }
            }
            true
        }
    }

    private fun setButtonPosition(button: Button, left: Int, top: Int) {
        val root = binding.root
        val maxLeft = (root.width - button.width).coerceAtLeast(0)
        val maxTop = (root.height - button.height).coerceAtLeast(0)
        val params = button.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = left.coerceIn(0, maxLeft)
        params.topMargin = top.coerceIn(0, maxTop)
        button.layoutParams = params
    }

    private fun saveButtonPosition(button: Button, key: String) {
        val root = binding.root
        val params = button.layoutParams as FrameLayout.LayoutParams
        val maxLeft = (root.width - button.width).coerceAtLeast(0)
        val maxTop = (root.height - button.height).coerceAtLeast(0)
        getSharedPreferences(PREF_FLOATING_BUTTONS, MODE_PRIVATE)
            .edit()
            .putFloat("$key.x", if (maxLeft == 0) 0f else params.leftMargin.toFloat() / maxLeft)
            .putFloat("$key.y", if (maxTop == 0) 0f else params.topMargin.toFloat() / maxTop)
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun startCamera() {
        setStatus(getString(R.string.status_opening_camera))
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (error: Exception) {
                Log.e(TAG, "camera provider failed", error)
                setStatus(getString(R.string.status_camera_failed, error.message ?: ""))
                return@addListener
            }
            val backCameraAvailable = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val selector = if (backCameraAvailable) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                setStatus(getString(R.string.status_camera_failed, "没有可用相机"))
                return@addListener
            }
            bindCamera(provider, selector, backCameraAvailable)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        if (captureInFlight.get()) return
        val provider = cameraProvider ?: run {
            setStatus(getString(R.string.status_camera_not_ready))
            return
        }
        val targetIsBackCamera = !isBackCamera
        val targetSelector = if (targetIsBackCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (!provider.hasCamera(targetSelector)) {
            setStatus(getString(R.string.status_camera_unavailable))
            return
        }
        bindCamera(provider, targetSelector, targetIsBackCamera)
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        backCamera: Boolean
    ) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        try {
            provider.unbindAll()
            imageCapture = null
            binding.switchCameraButton.isEnabled = false
            provider.bindToLifecycle(this, selector, preview, capture)
            cameraProvider = provider
            isBackCamera = backCamera
            imageCapture = capture
            binding.switchCameraButton.isEnabled = hasOtherCamera(provider, backCamera)
            setStatus(getString(R.string.status_ready))
        } catch (error: Exception) {
            Log.e(TAG, "bind camera failed", error)
            setStatus(getString(R.string.status_camera_failed, error.message ?: ""))
        }
    }

    private fun hasOtherCamera(provider: ProcessCameraProvider, backCamera: Boolean): Boolean {
        val otherSelector = if (backCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        return provider.hasCamera(otherSelector)
    }

    private fun showSettings() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val serverInput = EditText(this).apply {
            hint = getString(R.string.hint_server)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(serverUrl)
        }
        val directoryInput = EditText(this).apply {
            hint = getString(R.string.hint_dir)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(saveDirectory)
        }
        fields.addView(serverInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fields.addView(directoryInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        AlertDialog.Builder(this)
            .setTitle(R.string.action_settings)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_receive_mode) { _, _ ->
                startActivity(android.content.Intent(this, ServerActivity::class.java))
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                serverUrl = serverInput.text.toString().trim()
                saveDirectory = directoryInput.text.toString().trim()
                AppConfig.save(this, serverUrl, saveDirectory)
                setStatus(getString(R.string.status_settings_saved))
            }
            .show()
    }

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            setStatus(getString(R.string.status_camera_not_ready))
            return
        }
        if (!captureInFlight.compareAndSet(false, true)) return
        setStatus(getString(R.string.status_capturing))
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val previous = lastPhoto
                    lastPhoto = file
                    if (previous != null && previous != file) previous.delete()
                    runOnUiThread {
                        captureInFlight.set(false)
                        upload(file)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    runOnUiThread {
                        captureInFlight.set(false)
                        setStatus(getString(R.string.status_capture_failed, exception.message ?: ""))
                    }
                }
            }
        )
    }

    private fun uploadLast() {
        val file = lastPhoto
        if (file == null || !file.exists()) {
            setStatus(getString(R.string.status_no_photo))
            return
        }
        upload(file)
    }

    private fun upload(file: File) {
        val saveDir = saveDirectory
        if (serverUrl.isEmpty()) {
            setStatus(getString(R.string.status_no_server))
            return
        }
        setStatus(getString(R.string.status_uploading))
        setBusy(true)
        cameraExecutor.execute {
            val result = PhotoUploader.upload(serverUrl, saveDir, file)
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = { body ->
                        val saved = Regex("\"saved\"\\s*:\\s*\"([^\"]*)\"")
                            .find(body)
                            ?.groupValues
                            ?.get(1)
                        setStatus(getString(R.string.status_uploaded, saved ?: body))
                    },
                    onFailure = { error ->
                        setStatus(getString(R.string.status_upload_failed, error.message ?: ""))
                    }
                )
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.retakeButton.isEnabled = !busy
        binding.retryUploadButton.isEnabled = !busy
        binding.switchCameraButton.isEnabled =
            !busy && cameraProvider?.let { hasOtherCamera(it, isBackCamera) } == true
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    companion object {
        private const val TAG = "PhotoUpload"
        private const val PREF_FLOATING_BUTTONS = "floating_buttons"
        const val EXTRA_SERVER_URL = "server_url"
    }
}
