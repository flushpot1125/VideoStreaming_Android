// AudioCapture.kt
package com.example.videostream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioCapture(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val TAG = "AudioCapture"


    // 音声設定
    companion object {
        const val SAMPLE_RATE = 44100 // 44.1kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_FACTOR = 2 // バッファサイズの倍率
    }

    private val bufferSize by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        minBufferSize * BUFFER_SIZE_FACTOR
    }

    // 明示的な権限チェック - Lintに対応するための方法
    private fun checkAudioPermission(): Boolean {
        val permissionCheck = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )
        return permissionCheck == PackageManager.PERMISSION_GRANTED
    }

    // @RequiresPermission アノテーションを使用して権限が必要なことを明示
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startRecording(onAudioDataAvailable: (ByteArray) -> Unit) {
        withContext(Dispatchers.IO) {
            if (isRecording) return@withContext

            // 権限チェック - Lint警告対策のため明示的に行う
            if (!checkAudioPermission()) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                return@withContext
            }

            try {
                // Lint警告対策として、PermissionCheck後に初期化
                @Suppress("MissingPermission")  // 既にチェック済みなのでLint警告を抑制
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    return@withContext
                }

                audioRecord?.startRecording()
                isRecording = true
                Log.d(TAG, "Audio recording started with buffer size: $bufferSize")

                // 録音ループ
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        // 読み取ったデータのみを送信
                        val audioData = buffer.copyOfRange(0, readSize)
                        onAudioDataAvailable(audioData)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio recording", e)
            }
        }
    }

}