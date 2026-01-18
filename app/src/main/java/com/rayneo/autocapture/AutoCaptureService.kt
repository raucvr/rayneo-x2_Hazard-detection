package com.rayneo.autocapture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.*
import android.util.Log
import android.util.Range
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台相机拍照服务
 *
 * 支持两种模式：
 * 1. 单次拍照模式 - 通过 ADB 广播触发
 * 2. 持续检测模式 - 自动循环拍照并调用 VLM 分析危险
 */
class AutoCaptureService : Service() {

    companion object {
        const val TAG = "AutoCaptureService"

        // 基础操作
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_CAPTURE = "CAPTURE"

        // 持续检测模式
        const val ACTION_START_CONTINUOUS = "START_CONTINUOUS"
        const val ACTION_STOP_CONTINUOUS = "STOP_CONTINUOUS"

        const val CHANNEL_ID = "AutoCaptureChannel"
        const val NOTIFICATION_ID = 1001

        // 图片分辨率
        const val IMAGE_WIDTH = 1920
        const val IMAGE_HEIGHT = 1080
    }

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private val takePhoto = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private var cameraReady = AtomicBoolean(false)

    // 持续检测相关
    private var continuousMode = AtomicBoolean(false)
    private var analyzerJob: Job? = null
    private var dangerAnalyzer: DangerAnalyzer? = null
    private var alertManager: AlertManager? = null
    private var lastCapturedBytes: ByteArray? = null
    private val captureComplete = Object()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        createNotificationChannel()
        alertManager = AlertManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START, ACTION_CAPTURE -> {
                if (!isRunning.get()) {
                    startForegroundWithNotification("Camera service running")
                    startBackgroundThread()
                    openCamera()
                    isRunning.set(true)
                }

                if (intent.action == ACTION_CAPTURE) {
                    triggerCapture()
                }
            }

            ACTION_START_CONTINUOUS -> {
                // 检查是否通过 Intent 传递了 API Key
                intent.getStringExtra("api_key")?.let { apiKey ->
                    if (apiKey.isNotBlank()) {
                        ConfigManager.setApiKey(this, apiKey)
                        Log.i(TAG, "API Key set from intent")
                    }
                }

                if (!isRunning.get()) {
                    startForegroundWithNotification("Danger detection active")
                    startBackgroundThread()
                    openCamera()
                    isRunning.set(true)
                }
                startContinuousAnalysis()
            }

            ACTION_STOP_CONTINUOUS -> {
                stopContinuousAnalysis()
            }

            ACTION_STOP -> {
                stopContinuousAnalysis()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        stopContinuousAnalysis()
        isRunning.set(false)
        closeCamera()
        stopBackgroundThread()
        alertManager?.release()
        super.onDestroy()
    }

    private fun triggerCapture() {
        backgroundHandler?.postDelayed({
            if (cameraReady.get()) {
                takePhoto.set(true)
                Log.i(TAG, "Photo capture triggered")
            } else {
                Log.w(TAG, "Camera not ready, retrying...")
                backgroundHandler?.postDelayed({
                    takePhoto.set(true)
                }, 500)
            }
        }, if (cameraReady.get()) 0 else 1000)
    }

    /**
     * 启动持续危险检测循环
     */
    private fun startContinuousAnalysis() {
        if (continuousMode.get()) {
            Log.w(TAG, "Continuous mode already running")
            return
        }

        val apiKey = ConfigManager.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API Key not configured, cannot start continuous mode")
            return
        }

        dangerAnalyzer = DangerAnalyzer(apiKey)
        continuousMode.set(true)
        ConfigManager.setEnabled(this, true)

        val interval = ConfigManager.getInterval(this)
        Log.i(TAG, "Starting continuous analysis with interval: ${interval}s")

        analyzerJob = CoroutineScope(Dispatchers.IO).launch {
            // 等待相机准备好
            var waitCount = 0
            while (!cameraReady.get() && waitCount < 20) {
                delay(500)
                waitCount++
            }

            if (!cameraReady.get()) {
                Log.e(TAG, "Camera not ready after waiting")
                return@launch
            }

            Log.i(TAG, "Camera ready, starting analysis loop")

            while (isActive && continuousMode.get()) {
                try {
                    // 1. 拍照并获取 JPEG 字节
                    val imageBytes = captureAndGetBytes()
                    if (imageBytes == null) {
                        Log.w(TAG, "Failed to capture image, retrying...")
                        delay(1000)
                        continue
                    }

                    Log.d(TAG, "Captured image: ${imageBytes.size} bytes")

                    // 2. 调用 VLM 分析
                    val result = dangerAnalyzer?.analyzeImage(imageBytes)
                    Log.i(TAG, "Analysis result: isDanger=${result?.isDanger}, response=${result?.rawResponse}")

                    // 3. 如果危险，播放警报
                    if (result?.isDanger == true) {
                        Log.w(TAG, "DANGER DETECTED!")
                        withContext(Dispatchers.Main) {
                            alertManager?.playDangerAlert()
                        }
                    }

                    // 4. 等待间隔
                    delay(interval * 1000L)

                } catch (e: CancellationException) {
                    Log.i(TAG, "Analysis loop cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in analysis loop", e)
                    delay(2000)
                }
            }

            Log.i(TAG, "Analysis loop ended")
        }
    }

    /**
     * 停止持续危险检测
     */
    private fun stopContinuousAnalysis() {
        if (!continuousMode.get()) return

        Log.i(TAG, "Stopping continuous analysis")
        continuousMode.set(false)
        ConfigManager.setEnabled(this, false)
        analyzerJob?.cancel()
        analyzerJob = null
        dangerAnalyzer = null
    }

    /**
     * 拍照并直接返回 JPEG 字节数组（不保存文件）
     */
    private suspend fun captureAndGetBytes(): ByteArray? = withContext(Dispatchers.IO) {
        lastCapturedBytes = null

        // 触发拍照
        takePhoto.set(true)

        // 等待拍照完成（最多 3 秒）
        var waitTime = 0
        while (lastCapturedBytes == null && waitTime < 3000) {
            delay(100)
            waitTime += 100
        }

        return@withContext lastCapturedBytes
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Auto Capture Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "RayNeo Danger Detection"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Danger Detector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager!!.cameraIdList.firstOrNull() ?: run {
                Log.e(TAG, "No camera found")
                return
            }

            Log.i(TAG, "Opening camera: $cameraId")
            cameraManager!!.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "Camera opened")
            cameraDevice = camera
            backgroundHandler?.postDelayed({
                setupImageReader(camera)
            }, 100)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            cameraDevice?.close()
            cameraDevice = null
            cameraReady.set(false)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            cameraDevice?.close()
            cameraDevice = null
            cameraReady.set(false)
        }
    }

    private fun setupImageReader(camera: CameraDevice) {
        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                IMAGE_WIDTH, IMAGE_HEIGHT,
                ImageFormat.YUV_420_888, 3
            )

            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                try {
                    if (takePhoto.compareAndSet(true, false)) {
                        Log.i(TAG, "Capturing photo...")

                        // 转换为 JPEG 字节
                        val jpegBytes = yuvImageToJpegBytes(image)
                        lastCapturedBytes = jpegBytes

                        // 如果不是持续模式，保存到文件
                        if (!continuousMode.get() && jpegBytes != null) {
                            saveJpegToFile(jpegBytes)
                        }
                    }
                } finally {
                    image.close()
                }
            }, backgroundHandler)

            createCaptureSession(camera)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup ImageReader", e)
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 15))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val outputConfig = OutputConfiguration(imageReader!!.surface)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Capture session configured")
                        cameraCaptureSession = session
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        cameraReady.set(true)
                        Log.i(TAG, "Camera ready for capture")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        cameraReady.set(false)
                    }
                }
            )

            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    /**
     * 将 YUV 图像转换为 JPEG 字节数组
     */
    private fun yuvImageToJpegBytes(image: Image): ByteArray? {
        try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
            val jpegBytes = out.toByteArray()
            out.close()

            return jpegBytes
        } catch (e: Exception) {
            Log.e(TAG, "YUV to JPEG conversion failed", e)
            return null
        }
    }

    /**
     * 将 JPEG 字节保存到文件
     */
    private fun saveJpegToFile(jpegBytes: ByteArray) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "IMG_$timestamp.jpg"

            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(dcimDir, "Camera")
            if (!cameraDir.exists()) {
                cameraDir.mkdirs()
            }

            val photoFile = File(cameraDir, fileName)
            FileOutputStream(photoFile).use { fos ->
                fos.write(jpegBytes)
            }

            Log.i(TAG, "Photo saved: ${photoFile.absolutePath}")

            MediaScannerConnection.scanFile(
                this,
                arrayOf(photoFile.absolutePath),
                arrayOf("image/jpeg")
            ) { path, uri ->
                Log.i(TAG, "Media scanned: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraReady.set(false)
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            Log.i(TAG, "Camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
}
