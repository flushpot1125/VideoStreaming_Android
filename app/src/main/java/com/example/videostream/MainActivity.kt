package com.example.videostream

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface



class MainActivity : ComponentActivity() {
    // UI状態変数
    private var isStreamingVideo by mutableStateOf(false)
    private var connectedClients by mutableStateOf(0)
    private var serverUrl by mutableStateOf("http://...")

    // WakeLock
    private var wakeLock: PowerManager.WakeLock? = null

    // カメラ権限のリクエスト
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("Camera", "Permission granted")
        } else {
            Log.d("Camera", "Permission denied")
        }
    }

    // 必要な権限のリスト
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_CAMERA,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // 複数権限のリクエスト
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("Permissions", "All permissions granted")
        } else {
            Log.d("Permissions", "Some permissions denied")
            // ユーザーに権限の必要性を説明
            showPermissionsExplanationDialog()
        }
    }

    // サービス状態を受信するためのブロードキャストレシーバー
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.videostream.SERVICE_STARTED" -> {
                    isStreamingVideo = true
                    Log.d("MainActivity", "サービス開始通知を受信")
                    updateServerUrl()
                }
                "com.example.videostream.SERVICE_STOPPED" -> {
                    isStreamingVideo = false
                    Log.d("MainActivity", "サービス停止通知を受信")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // カメラ権限を確認
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 必要な権限を確認
        checkRequiredPermissions()

        // IPアドレスを初期化
        updateServerUrl()

        // サービス状態レシーバーを登録
        val intentFilter = IntentFilter().apply {
            addAction("com.example.videostream.SERVICE_STARTED")
            addAction("com.example.videostream.SERVICE_STOPPED")
        }

        // すべてのAndroidバージョンで動作する方法
        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )


        enableEdgeToEdge()
        setContent {
            MimamoriCameraTheme {
                MimamoriCameraScreen(
                    isStreaming = isStreamingVideo,
                    connectedClients = connectedClients,
                    serverUrl = serverUrl,
                    onStreamingChanged = { isEnabled ->
                        isStreamingVideo = isEnabled
                        if (isEnabled) {
                            startMimamoriService()
                        } else {
                            stopMimamoriService()
                        }
                    }
                )
            }
        }

        // CPU起動状態を維持するためのWakeLock取得
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MimamoriCamera:WakeLockTag"
        )
        wakeLock?.acquire(10*60*1000L) // 10分間のタイムアウト

        // 画面オン/オフの監視を登録
        registerScreenReceiver()
    }

    override fun onResume() {
        super.onResume()

        // UIの状態をサービスの実際の状態と同期
        val serviceRunning = isServiceRunning(CameraServerService::class.java)
        if (isStreamingVideo != serviceRunning) {
            isStreamingVideo = serviceRunning
            Log.d("MainActivity", "サービス状態とUI状態を同期: $serviceRunning")
        }

        // 以下は既存のコード
        if (isStreamingVideo) {
            acquireWakeLock()
        }

        // サーバーURLを更新
        updateServerUrl()
    }

    // WakeLockを取得するヘルパーメソッド
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(10*60*1000L) // 10分間のタイムアウト
        }
    }

    @Composable
    fun MimamoriCameraTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = lightColorScheme(),
            content = content
        )
    }

    @Composable
    fun MimamoriCameraScreen(
        isStreaming: Boolean,
        connectedClients: Int,
        serverUrl: String,
        onStreamingChanged: (Boolean) -> Unit
    ) {
        // 画面の向きを取得
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val screenHeight = configuration.screenHeightDp.dp
        val isLandscape = screenWidth > screenHeight

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isLandscape) {
                // 横向きレイアウト
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左側にタイトル
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "みまもりカメラ",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }

                    // 右側に操作UI
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
                    ) {
                        // 映像送信スイッチ
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "映像を送る",
                                fontSize = 22.sp
                            )
                            Switch(
                                checked = isStreaming,
                                onCheckedChange = onStreamingChanged,
                                modifier = Modifier.size(width = 60.dp, height = 40.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            )
                        }

                        // カメラのIPアドレス
                        Text(
                            text = "カメラのIPアドレス： $serverUrl",
                            fontSize = 18.sp
                        )

                    }
                }
            } else {
                // 縦向きレイアウト
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(40.dp)
                ) {
                    // タイトル
                    Text(
                        text = "みまもりカメラ",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 40.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 映像送信スイッチ
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "映像を送る",
                            fontSize = 24.sp
                        )
                        Switch(
                            checked = isStreaming,
                            onCheckedChange = onStreamingChanged,
                            modifier = Modifier.size(width = 60.dp, height = 40.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        )
                    }

                    // カメラのIPアドレス
                    Text(
                        text = "カメラのIPアドレス： $serverUrl",
                        fontSize = 20.sp
                    )

                }
            }
        }
    }

    // IPアドレスを更新するメソッド
    private fun updateServerUrl() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ipAddress = getLocalIpAddress()
            lifecycleScope.launch(Dispatchers.Main) {
                serverUrl = "http://$ipAddress:8080"
            }
        }
    }

    // IPアドレス取得
    private fun getLocalIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting IP address", e)
        }
        return "Unknown IP"
    }

    // プレビュー関数（縦向き）
    @Preview(showBackground = true, widthDp = 360, heightDp = 640)
    @Composable
    fun PortraitPreview() {
        MimamoriCameraTheme {
            MimamoriCameraScreen(
                isStreaming = true,
                connectedClients = 2,
                serverUrl = "http://192.168.1.100:8080",
                onStreamingChanged = {}
            )
        }
    }

    // プレビュー関数（横向き）
    @Preview(showBackground = true, widthDp = 640, heightDp = 360)
    @Composable
    fun LandscapePreview() {
        MimamoriCameraTheme {
            MimamoriCameraScreen(
                isStreaming = true,
                connectedClients = 2,
                serverUrl = "http://192.168.1.100:8080",
                onStreamingChanged = {}
            )
        }
    }

    // サービス開始処理
    private fun startMimamoriService() {
        // 権限チェック
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (permission in requiredPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                    Log.e("Permission", "Missing permission: $permission")
                    checkRequiredPermissions()  // 権限リクエスト再実行
                    return  // 権限がない場合は処理を中断
                }
            }
        }

        // サービスに開始コマンドを送信
        val serviceIntent = Intent(this, CameraServerService::class.java)

        // Android 8.0以上ではフォアグラウンドサービスが必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // サービス停止処理
    private fun stopMimamoriService() {
        val serviceIntent = Intent(this, CameraServerService::class.java)
        stopService(serviceIntent)  // stopServiceを使用して停止
    }

    // 画面オン/オフ検出用レシーバー
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("MainActivity", "Screen OFF - Preparing for sleep")
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("MainActivity", "Screen ON - Resuming from sleep")
                    checkServiceStatus()
                }
            }
        }
    }

    // 画面オン/オフ検出レシーバーの登録
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    // サービス状態確認
    private fun checkServiceStatus() {
        val serviceRunning = isServiceRunning(CameraServerService::class.java)
        if (!serviceRunning && isStreamingVideo) {
            Log.d("MainActivity", "Service not running, restarting")
            startMimamoriService()
        } else {
            // UI状態をサービスの実際の状態に同期
            if (isStreamingVideo != serviceRunning) {
                isStreamingVideo = serviceRunning
                Log.d("MainActivity", "サービス状態とUI状態を同期: $serviceRunning")
            }
        }
    }

    // サービスが実行中かチェック - より確実なチェック方法
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        // Android 11以降はgetRunningServicesの代わりにActivityManager.RunningAppProcessesを使う
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // サービス専用の確認方法
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == serviceClass.name }
        } else {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == serviceClass.name }
        }
    }

    private fun checkRequiredPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest)
        }
    }

    private fun showPermissionsExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("権限が必要です")
            .setMessage("このアプリはカメラとマイク、およびフォアグラウンドサービスの権限が必要です。設定から権限を有効にしてください。")
            .setPositiveButton("設定") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onDestroy() {
        // WakeLockを解放
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        // 画面オン/オフの監視を解除
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }

        // サービス状態レシーバーを解除
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering service state receiver", e)
        }

        super.onDestroy()
    }
}