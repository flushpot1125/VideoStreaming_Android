package com.example.videostream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraCapture(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var captureSession: CameraCaptureSession? = null
    private var isCapturing = false

    // フレームコールバック
    private var frameCallback: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    suspend fun startCamera(onFrameAvailable: (ByteArray) -> Unit) {
        frameCallback = onFrameAvailable
        startBackgroundThread()

        // カメラIDの取得
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]  // 通常、背面カメラが0

        // プレビューサイズを設定
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        // ImageReaderを設定
        imageReader = ImageReader.newInstance(
            640, 480,  // 解像度を下げる（パフォーマンスのため）
            ImageFormat.JPEG,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    // フレームコールバック経由でフレームデータを返す
                    frameCallback?.invoke(bytes)

                    image.close()
                }
            }, backgroundHandler)
        }

        // カメラをオープン
        cameraOpenCloseLock.acquire()
        try {
            suspendCoroutine<Unit> { continuation ->
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        cameraOpenCloseLock.release()
                        continuation.resume(Unit)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraOpenCloseLock.release()
                        device.close()
                        cameraDevice = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraOpenCloseLock.release()
                        device.close()
                        cameraDevice = null
                        Log.e("CameraCapture", "Camera Error: $error")
                    }
                }, backgroundHandler)
            }

            // キャプチャセッションを開始
            startCaptureSession()
        } catch (e: Exception) {
            Log.e("CameraCapture", "Failed to open camera", e)
        }
    }

    private suspend fun startCaptureSession() {
        val device = cameraDevice ?: return
        val surfaces = listOf(imageReader!!.surface)

        suspendCoroutine<Unit> { continuation ->
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val captureRequest = device.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            addTarget(imageReader!!.surface)
                            // フレームレートを下げる（パフォーマンス向上のため）
                            set(CaptureRequest.JPEG_QUALITY, 70)
                        }

                        captureSession?.setRepeatingRequest(
                            captureRequest.build(),
                            null,
                            backgroundHandler
                        )

                        isCapturing = true
                    } catch (e: Exception) {
                        Log.e("CameraCapture", "Failed to start capture session", e)
                    }
                    continuation.resume(Unit)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraCapture", "Failed to configure capture session")
                    continuation.resume(Unit)
                }
            }, backgroundHandler)
        }
    }

    fun stopCamera() {
        try {
            isCapturing = false
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            frameCallback = null
        } catch (e: Exception) {
            Log.e("CameraCapture", "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("CameraCapture", "Error stopping background thread", e)
        }
    }

    suspend fun restart() {
        stopCamera()
        // 少し待機してからカメラを再開する
        Thread.sleep(500)
        startCamera(frameCallback ?: return)
    }
}