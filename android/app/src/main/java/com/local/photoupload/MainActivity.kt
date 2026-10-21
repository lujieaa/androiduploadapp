package com.local.photoupload

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
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
        binding.settingsButton.setOnClickListener { showSettings() }

        if (hasCameraPermission() && hasMediaReadPermission()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }
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
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> {
                    setStatus(getString(R.string.status_camera_failed, "没有可用相机"))
                    return@addListener
                }
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            imageCapture = capture
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
            } catch (error: Exception) {
                Log.e(TAG, "bind camera failed", error)
                setStatus(getString(R.string.status_camera_failed, error.message ?: ""))
                return@addListener
            }
            setStatus(getString(R.string.status_ready))
        }, ContextCompat.getMainExecutor(this))
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
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    companion object {
        private const val TAG = "PhotoUpload"
        const val EXTRA_SERVER_URL = "server_url"
    }
}
