package com.example.videostream

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

import android.os.Handler
import android.os.HandlerThread

class CameraServerService : Service() {
    companion object {
        private const val TAG = "CameraServerService"
        private const val SERVER_PORT = 8080
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "camera_server_channel"

        // ブロードキャストアクション
        const val ACTION_SERVICE_STARTED = "com.example.videostream.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.example.videostream.SERVICE_STOPPED"

        // カメラ設定
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480
        private const val CAMERA_FPS = 15
    }

    // WebSocketセッション情報を保持するクラス
    data class WebSocketSessionData(
        val session: WebSocketSession,
        val id: String,
        var lastActivity: Long = System.currentTimeMillis()
    )

    private val activeConnections = CopyOnWriteArrayList<WebSocketSessionData>()
    private var server: ApplicationEngine? = null
    private var serverJob: Job? = null
    private val isFrameSendingInProgress = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val frameSendingMutex = Mutex()

    // カメラとマイクのキャプチャ状態
    private var isCameraRunning = false
    private var isAudioRunning = false

    // カメラ関連
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)



    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // 通知チャンネルの作成
        createNotificationChannel()

        // Foreground Service として起動 - これは必須
        startForegroundWithNotification()

        // サーバーを起動
        startServer()

        // サービス開始をブロードキャスト
        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
        Log.d(TAG, "サービス開始ブロードキャストを送信")
    }

    override fun onDestroy() {
        stopServer()

        // サービス停止をブロードキャスト
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        Log.d(TAG, "サービス停止ブロードキャストを送信")

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "カメラサーバー"
            val descriptionText = "カメラストリーミングサーバーの実行状態"
            val importance = NotificationManager.IMPORTANCE_LOW // 低優先度で通知音を鳴らさない
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            // Register the channel with the system
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        // メインアクティビティを開くためのPendingIntent
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // IPアドレスを取得
        val ipAddress = getIPAddress()

        // 通知を構築
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("カメラサーバー実行中")
            .setContentText("接続先: $ipAddress:$SERVER_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // foregroundサービスとして起動
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started with notification")
    }

    // IPアドレス取得
    private fun getIPAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // ループバックアドレスではなく、IPv4アドレスのみを取得
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IPアドレス取得エラー: ${e.message}", e)
        }

        return "127.0.0.1" // デフォルトのループバックアドレス
    }

    private fun startServer() {
        // Netty関連の問題を防ぐためのシステムプロパティを設定
        System.setProperty("io.netty.noUnsafe", "true")
        System.setProperty("io.netty.nativeEpoll", "false")
        System.setProperty("io.netty.noKeySetOptimization", "true")

        serverJob = serviceScope.launch {
            try {
                val environment = applicationEngineEnvironment {
                    // SLF4Jロガーを使用
                    log = LoggerFactory.getLogger(TAG)

                    // コネクター設定
                    connector {
                        host = "0.0.0.0"
                        port = SERVER_PORT
                    }

                    // モジュール設定
                    module {
                        install(WebSockets) {
                            pingPeriodMillis = 15000 // 15秒ごとにping
                            timeoutMillis = 30000    // 30秒タイムアウト
                            maxFrameSize = 64 * 1024 // 最大フレームサイズ制限
                        }

                        // ルーティング設定
                        routing {
                            // 静的ファイル処理
                            get("/") {
                                try {
                                    // Android固有の方法でリソースにアクセス
                                    val indexHtml = application.environment.classLoader.getResourceAsStream("static/index.html")?.bufferedReader()?.use { it.readText() }
                                        ?: this@CameraServerService.assets.open("static/index.html").bufferedReader().use { it.readText() }
                                    call.respondText(indexHtml, ContentType.Text.Html)
                                } catch (e: Exception) {
                                    Log.e(TAG, "HTML読み込みエラー", e)
                                    call.respondText("HTML file not found: ${e.message}", ContentType.Text.Plain, HttpStatusCode.NotFound)
                                }
                            }

                            get("/client.js") {
                                try {
                                    val jsContent = application.environment.classLoader.getResourceAsStream("static/client.js")?.bufferedReader()?.use { it.readText() }
                                        ?: this@CameraServerService.assets.open("static/client.js").bufferedReader().use { it.readText() }
                                    call.respondText(jsContent, ContentType.Text.JavaScript)
                                } catch (e: Exception) {
                                    Log.e(TAG, "JS読み込みエラー", e)
                                    call.respond(HttpStatusCode.NotFound, "JavaScript not found")
                                }
                            }

                            // 診断用エンドポイント
                            get("/test") {
                                call.respondText("サーバー正常動作中", ContentType.Text.Plain)
                            }

                            // システム状態エンドポイント
                            get("/status") {
                                val statusData = mapOf(
                                    "status" to "running",
                                    "connections" to activeConnections.size,
                                    "uptime" to System.currentTimeMillis(),
                                    "version" to "1.0"
                                )
                                call.respondText(JSONObject(statusData).toString(), ContentType.Application.Json)
                            }

                            // WebSocketエンドポイント
                            webSocket("/media") {
                                val sessionId = "client_${System.currentTimeMillis()}_${Random.nextInt(10000)}"
                                val sessionData = WebSocketSessionData(this, sessionId)

                                try {
                                    // セッションを追加
                                    activeConnections.add(sessionData)
                                    Log.d(TAG, "クライアント接続: ID=$sessionId - 現在 ${activeConnections.size} 台")

                                    // 接続情報をより詳しく記録
                                    val headers = call.request.headers
                                    val userAgent = headers["User-Agent"] ?: "Unknown"
                                    val host = call.request.local.remoteHost
                                    Log.d(TAG, "接続クライアント詳細: ID=$sessionId, IP=$host, UserAgent=$userAgent")

                                    try {
                                        // メタデータ送信
                                        send(Frame.Text(JSONObject().apply {
                                            put("type", "metadata")
                                            put("audioSampleRate", 44100) // 標準的なサンプルレート
                                            put("audioChannels", 1) // モノラル
                                            put("audioFormat", "pcm16") // 16ビットPCM
                                            put("clientId", sessionId)
                                            put("timestamp", System.currentTimeMillis())
                                            put("clientCount", activeConnections.size)
                                            put("videoWidth", CAMERA_WIDTH)
                                            put("videoHeight", CAMERA_HEIGHT)
                                            put("videoFps", CAMERA_FPS)
                                        }.toString()))

                                        // カメラとマイクがない場合は起動
                                        if (activeConnections.size == 1) {
                                            ensureMediaCaptureStarted()
                                        }

                                        // ハートビート処理
                                        val heartbeatJob = launch {
                                            try {
                                                while (isActive) {
                                                    delay(5000) // 5秒ごとにハートビート
                                                    try {
                                                        send(Frame.Text(JSONObject().apply {
                                                            put("type", "heartbeat")
                                                            put("timestamp", System.currentTimeMillis())
                                                            put("clientCount", activeConnections.size)
                                                        }.toString()))

                                                        // セッションのアクティビティ時間を更新
                                                        val sessionToUpdate = activeConnections.find { it.session == this@webSocket }
                                                        sessionToUpdate?.lastActivity = System.currentTimeMillis()
                                                    } catch (e: Exception) {
                                                        Log.w(TAG, "ハートビート送信失敗: ${e.message}")
                                                        break
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.d(TAG, "ハートビートループ終了")
                                            }
                                        }

                                        // クライアントメッセージ受信処理
                                        for (frame in incoming) {
                                            when (frame) {
                                                is Frame.Text -> {
                                                    val text = frame.readText()

                                                    // アクティビティ時間を更新
                                                    val sessionToUpdate = activeConnections.find { it.session == this }
                                                    sessionToUpdate?.lastActivity = System.currentTimeMillis()

                                                    // ping要求には即座にpongで応答
                                                    if (text == "ping") {
                                                        try {
                                                            send(Frame.Text("pong"))
                                                        } catch (e: Exception) {
                                                            Log.w(TAG, "Pong送信失敗: ${e.message}")
                                                        }
                                                    }
                                                }
                                                else -> { /* 他のフレームタイプは無視 */ }
                                            }
                                        }

                                        // ハートビートジョブをキャンセル
                                        heartbeatJob.cancel()

                                    } catch (e: ClosedReceiveChannelException) {
                                        Log.d(TAG, "WebSocketチャネル正常クローズ: $sessionId")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "WebSocketエラー: $sessionId - ${e.message}", e)
                                    }
                                } finally {
                                    // 接続終了時の処理
                                    activeConnections.removeIf { it.session == this }
                                    Log.d(TAG, "クライアント切断: $sessionId - 残り ${activeConnections.size} 台")

                                    // 全クライアントが切断されたらキャプチャを停止
                                    if (activeConnections.isEmpty()) {
                                        stopMediaCapture()
                                    }
                                }
                            }
                        }
                    }
                }

                try {
                    // CIOエンジンの正しい使用方法
                    Log.d(TAG, "CIOエンジンでサーバー起動中...")
                    server = embeddedServer(CIO, environment) {
                        // CIOエンジン設定 - Ktor 2.3.2で利用可能なオプションのみを使用
                        connectionIdleTimeoutSeconds = 45  // 接続アイドルタイムアウト
                    }
                    server?.start(wait = false)
                    Log.d(TAG, "サーバー起動成功: ポート$SERVER_PORT (CIOエンジン)")
                } catch (e: Exception) {
                    Log.e(TAG, "サーバー起動中にエラー: ${e.message}", e)
                    e.printStackTrace()
                    throw e
                }
                // 接続状況の定期チェック
                while (isActive) {
                    delay(10000)
                    cleanupInactiveConnections()
                    Log.d(TAG, "現在の接続数: ${activeConnections.size}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "サーバー起動エラー: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    private fun stopServer() {
        Log.d(TAG, "サーバー停止処理開始")

        // WebSocket接続をすべて閉じる
        serviceScope.launch {
            activeConnections.forEach { sessionData ->
                try {
                    sessionData.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "サーバー停止"))
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket切断エラー: ${e.message}")
                }
            }
        }

        // メディアキャプチャ停止
        stopMediaCapture()

        // サーバー停止 - グレースフルシャットダウン
        try {
            server?.stop(1000, 2000)
            Log.d(TAG, "サーバー正常停止完了")
        } catch (e: Exception) {
            Log.e(TAG, "サーバー停止中にエラー: ${e.message}", e)
        }

        serverJob?.cancel()

        // クリーンアップ
        activeConnections.clear()
        Log.d(TAG, "サーバー停止処理完了")
    }

    // 一定時間アクティビティがないクライアントを切断
    private fun cleanupInactiveConnections() {
        val now = System.currentTimeMillis()
        val expiredTime = 30000 // 30秒間応答がなければ切断

        val expiredSessions = activeConnections.filter { now - it.lastActivity > expiredTime }

        if (expiredSessions.isNotEmpty()) {
            serviceScope.launch {
                expiredSessions.forEach { session ->
                    try {
                        session.session.close(CloseReason(CloseReason.Codes.NORMAL, "タイムアウト"))
                    } catch (e: Exception) {
                        Log.e(TAG, "タイムアウト切断エラー: ${e.message}")
                    }
                }

                activeConnections.removeAll(expiredSessions)
                Log.d(TAG, "${expiredSessions.size}台のタイムアウトしたクライアントを削除 (残り: ${activeConnections.size})")
            }
        }
    }

    // カメラとマイクのキャプチャ開始
    private fun ensureMediaCaptureStarted() {
        if (!isCameraRunning) {
            startCamera()
        }
        if (!isAudioRunning) {
            startAudio()
        }
    }

    // キャプチャ停止
    private fun stopMediaCapture() {
        if (isCameraRunning) {
            stopCamera()
        }
        if (isAudioRunning) {
            stopAudio()
        }
    }


    // バックグラウンドスレッド起動
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
        }
        backgroundThread?.let { thread ->
            backgroundHandler = Handler(thread.looper)
        }
    }

    // バックグラウンドスレッド停止
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "バックグラウンドスレッド停止エラー", e)
        }
    }

    // カメラ処理 - 実際のカメラから映像を取得
    private fun startCamera() {
        if (isCameraRunning) {
            Log.d(TAG, "カメラは既に実行中です")
            return
        }

        startBackgroundThread()

        serviceScope.launch(Dispatchers.IO) {
            try {
                isCameraRunning = true
                Log.d(TAG, "カメラキャプチャ開始")

                // カメラマネージャーを取得
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

                // 背面カメラのIDを取得
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    facing == CameraCharacteristics.LENS_FACING_BACK
                } ?: throw Exception("背面カメラが見つかりません")

                // カメラの特性を取得
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw RuntimeException("Cannot get available preview/video sizes")

                // 適切なプレビューサイズを選択
                val previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    CAMERA_WIDTH,
                    CAMERA_HEIGHT
                )

                // ImageReaderを作成
                imageReader = ImageReader.newInstance(
                    previewSize.width, previewSize.height,
                    ImageFormat.JPEG, 2
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        // 新しい画像が利用可能になったとき
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.capacity())
                            buffer.get(bytes)

                            // JPEG画像をクライアントに送信
                            val mediaFrame = MediaFrame(
                                type = FrameType.VIDEO,
                                data = bytes,
                                timestamp = System.currentTimeMillis()
                            )

                            sendMediaFrameToAllClients(mediaFrame)

                        } catch (e: Exception) {
                            Log.e(TAG, "画像処理エラー: ${e.message}", e)
                        } finally {
                            image.close()
                        }
                    }, backgroundHandler)
                }

                // カメラデバイスを開く
                if (ActivityCompat.checkSelfPermission(
                        this@CameraServerService,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "カメラ権限がありません")
                    return@launch
                }

                cameraOpenCloseLock.acquire()

                try {
                    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            createCameraPreviewSession()
                            cameraOpenCloseLock.release()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            cameraOpenCloseLock.release()
                            camera.close()
                            cameraDevice = null
                            Log.d(TAG, "カメラ切断")
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            cameraOpenCloseLock.release()
                            camera.close()
                            cameraDevice = null
                            Log.e(TAG, "カメラオープンエラー: $error")
                        }
                    }, backgroundHandler)
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "カメラアクセスエラー: ${e.message}", e)
                    cameraOpenCloseLock.release()
                }

                // カメラ起動を待機
                while (isCameraRunning && cameraDevice == null) {
                    delay(100)
                }

                // カメラ実行中のメインループ
                while (isCameraRunning && isActive) {
                    // キャプチャセッションは別スレッドで実行中
                    // 定期的なステータス確認
                    delay(1000)
                }

            } catch (e: Exception) {
                Log.e(TAG, "カメラ処理エラー: ${e.message}", e)
            } finally {
                // リソース解放
                cameraCaptureSession?.close()
                cameraCaptureSession = null

                cameraDevice?.close()
                cameraDevice = null

                imageReader?.close()
                imageReader = null

                stopBackgroundThread()

                isCameraRunning = false
                Log.d(TAG, "カメラキャプチャ終了")
            }
        }
    }

    // プレビューセッション作成
    private fun createCameraPreviewSession() {
        try {
            // プレビューセッション設定
            val surface = imageReader?.surface ?: return

            // キャプチャリクエスト作成
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            captureRequestBuilder?.addTarget(surface)

            // 自動フォーカス設定
            captureRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            // 自動露出設定
            captureRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

            // 定期的にJPEG画像をキャプチャ
            captureRequestBuilder?.set(
                CaptureRequest.JPEG_QUALITY,
                80.toByte() // JPEG品質 (0-100)
            )

            // セッション作成
            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        // セッション設定完了
                        cameraCaptureSession = session
                        try {
                            // 繰り返しキャプチャを開始
                            val captureRequest = captureRequestBuilder?.build()
                            session.setRepeatingRequest(
                                captureRequest!!,
                                object : CameraCaptureSession.CaptureCallback() {
                                    // キャプチャコールバック
                                },
                                backgroundHandler
                            )
                            Log.d(TAG, "カメラプレビュー開始")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "プレビュー設定エラー: ${e.message}", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "カメラセッション設定失敗")
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "プレビューセッション作成エラー: ${e.message}", e)
        }
    }

    // 最適なサイズを選択
    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        // 理想的なアスペクト比に近いサイズを選択
        val targetRatio = width.toFloat() / height

        return choices
            .filter { it.height <= height && it.width <= width }
            .minByOrNull {
                Math.abs(it.width.toFloat() / it.height - targetRatio) +
                        Math.abs(it.width - width) / width.toFloat() +
                        Math.abs(it.height - height) / height.toFloat()
            } ?: choices[0] // 適切なサイズが見つからない場合は最初のサイズを使用
    }

    private fun stopCamera() {
        try {
            cameraOpenCloseLock.acquire()

            cameraCaptureSession?.close()
            cameraCaptureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

            stopBackgroundThread()

        } catch (e: Exception) {
            Log.e(TAG, "カメラ停止エラー: ${e.message}", e)
        } finally {
            isCameraRunning = false
            cameraOpenCloseLock.release()
            Log.d(TAG, "カメラキャプチャ停止")
        }
    }

    // 音声処理
    // 音声処理メソッドの改善
    private fun startAudio() {
        if (isAudioRunning) {
            Log.d(TAG, "マイクは既に実行中です")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            var audioRecord: AudioRecord? = null

            try {
                isAudioRunning = true
                Log.d(TAG, "音声キャプチャ開始")

                // 音声パラメータ - 標準的な値に設定
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bytesPerSample = 2 // 16bit = 2バイト

                // バッファサイズ計算
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, channelConfig, audioFormat
                )

                // より小さいフレームサイズを使用して頻繁に送信
                val frameSize = minBufferSize / 4
                val bufferSize = minBufferSize * 2

                // 権限チェック
                if (ActivityCompat.checkSelfPermission(
                        this@CameraServerService,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "マイク権限がありません")
                    return@launch
                }

                // AudioRecordインスタンス生成
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                // 録音開始
                audioRecord.startRecording()
                Log.d(TAG, "AudioRecord開始: サンプリングレート=${sampleRate}Hz, バッファサイズ=${bufferSize}バイト")

                // 音声読み取りバッファ
                val audioBuffer = ByteArray(frameSize)

                // 録音ループ
                while (isAudioRunning && isActive) {
                    val readResult = audioRecord.read(audioBuffer, 0, audioBuffer.size)

                    if (readResult > 0) {
                        // データ量を確認してログに出力
                        Log.d(TAG, "音声データ読み取り: ${readResult}バイト")

                        // 実際に読み込まれたサイズのデータを送信
                        val actualAudioData = if (readResult < audioBuffer.size) {
                            audioBuffer.copyOf(readResult)
                        } else {
                            audioBuffer
                        }

                        // MediaFrameを作成
                        val mediaFrame = MediaFrame(
                            type = FrameType.AUDIO,
                            data = actualAudioData,
                            timestamp = System.currentTimeMillis()
                        )

                        // フレームを送信
                        sendMediaFrameToAllClients(mediaFrame)
                    }

                    // より頻繁に送信する（20ms間隔 = 約50パケット/秒）
                    delay(20)
                }

            } catch (e: Exception) {
                Log.e(TAG, "マイク処理エラー: ${e.message}", e)
            } finally {
                // リソース解放
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "音声リソース解放エラー: ${e.message}", e)
                }

                isAudioRunning = false
                Log.d(TAG, "音声キャプチャ終了")
            }
        }
    }

    private fun stopAudio() {
        // 音声キャプチャ停止処理
        isAudioRunning = false
        Log.d(TAG, "音声キャプチャ停止")
    }

    // メディアフレーム送信 - マルチクライアント対応の改善版
// 改善されたsendMediaFrameToAllClientsメソッド
    fun sendMediaFrameToAllClients(mediaFrame: MediaFrame) {
        // アクティブな接続があるか確認
        if (activeConnections.isEmpty()) {
            return
        }

        serviceScope.launch {
            try {
                val isVideo = mediaFrame.type == FrameType.VIDEO

                // 各クライアントで共有可能なヘッダー部分を一度だけ構築
                val frameHeaderData = ByteArray(5)
                frameHeaderData[0] = if (isVideo) 0.toByte() else 1.toByte()
                val size = mediaFrame.data.size
                frameHeaderData[1] = (size shr 24 and 0xFF).toByte()
                frameHeaderData[2] = (size shr 16 and 0xFF).toByte()
                frameHeaderData[3] = (size shr 8 and 0xFF).toByte()
                frameHeaderData[4] = (size and 0xFF).toByte()

                // 各クライアント向けの送信処理を並列で実行
                val sendJobs = activeConnections.map { client ->
                    launch {
                        try {
                            // 各クライアントごとに新しいByteArrayを作成
                            val frameData = ByteArray(mediaFrame.data.size + 5)

                            // ヘッダーをコピー
                            System.arraycopy(frameHeaderData, 0, frameData, 0, 5)

                            // データ本体をコピー
                            System.arraycopy(mediaFrame.data, 0, frameData, 5, mediaFrame.data.size)

                            // 新しいWebSocketフレームを作成して送信
                            val frame = Frame.Binary(true, frameData)

                            // タイムアウト付きで送信
                            withTimeout(200) { // 200msのタイムアウト
                                client.session.send(frame)
                            }

                            // 送信成功を記録
                            client.lastActivity = System.currentTimeMillis()

                        } catch (e: Exception) {
                            when (e) {
                                is TimeoutCancellationException -> {
                                    Log.w(TAG, "クライアント ${client.id} への送信タイムアウト")
                                    // タイムアウトしたクライアントを削除
                                    activeConnections.remove(client)
                                }
                                is ClosedReceiveChannelException -> {
                                    Log.d(TAG, "クライアント ${client.id} は切断済み")
                                    activeConnections.remove(client)
                                }
                                else -> {
                                    Log.e(TAG, "クライアント ${client.id} への送信エラー: ${e.message}")
                                    activeConnections.remove(client)
                                }
                            }
                        }
                    }
                }

                // すべての送信ジョブが完了するのを待つ
                joinAll(*sendJobs.toTypedArray())

                // 送信後の接続数をログに出力
                if (isVideo && activeConnections.size > 1) {
                    Log.d(TAG, "動画フレーム送信完了: ${activeConnections.size}台のクライアントに送信")
                }

            } catch (e: Exception) {
                Log.e(TAG, "フレーム送信エラー: ${e.message}", e)
            }
        }
    }


    // フレームオブジェクトをコピー
    private fun Frame.Binary.copy(): Frame.Binary {
        return Frame.Binary(fin, data.clone(), rsv1, rsv2, rsv3)
    }


}