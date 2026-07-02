package com.zsxh.audiorelay.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zsxh.audiorelay.R
import com.zsxh.audiorelay.audio.AudioCapture
import com.zsxh.audiorelay.audio.OpusEncoder
import com.zsxh.audiorelay.network.RelayWebSocket
import com.zsxh.audiorelay.ui.MainActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务 — 负责音频采集 + WebSocket 传输
 */
class AudioCaptureService : Service() {
    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "audio_relay_channel"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 1
        private const val FRAME_SIZE = 960  // 20ms @ 48kHz
        private const val PCM_FRAME_BYTES = FRAME_SIZE * CHANNELS * 2  // 16-bit = 2 bytes/sample

        const val ACTION_START = "com.zsxh.audiorelay.START"
        const val ACTION_STOP = "com.zsxh.audiorelay.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SERVER_URL = "server_url"
    }

    inner class LocalBinder : Binder() {
        val service: AudioCaptureService get() = this@AudioCaptureService
    }

    private val binder = LocalBinder()
    private var webSocket: RelayWebSocket? = null
    private var audioCapture: AudioCapture? = null
    private var opusEncoder: OpusEncoder? = null
    private var captureThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var sequenceNumber: Int = 0

    // 状态回调
    var onStateChanged: ((State) -> Unit)? = null
    var onStatsUpdate: ((Long, Long) -> Unit)? = null  // framesSent, bytesSent

    enum class State { IDLE, CONNECTING, CONNECTED, STREAMING, ERROR }

    @Volatile var currentState = State.IDLE; private set

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "default"
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "wss://audio.zsxh.me/ws"

                startForegroundNotification()
                startCapture(resultCode, resultData, roomId, password, serverUrl)
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent?,
        roomId: String,
        password: String,
        serverUrl: String
    ) {
        if (isRunning.getAndSet(true)) return

        updateState(State.CONNECTING)

        // 1. 建立 WebSocket 连接
        webSocket = RelayWebSocket(serverUrl).apply {
            onConnected = {
                Log.i(TAG, "WS connected, joining room: $roomId")
                joinProducer(roomId, password)
            }
            onJoined = { role, consumers ->
                Log.i(TAG, "Joined as $role, consumers: $consumers")
                updateState(State.STREAMING)
                startAudioThread(resultCode, resultData)
            }
            onError = { msg ->
                Log.e(TAG, "WS error: $msg")
                updateState(State.ERROR)
            }
            onDisconnected = { code, reason ->
                Log.i(TAG, "WS disconnected: $code $reason")
                if (isRunning.get()) {
                    updateState(State.CONNECTING)
                }
            }
        }
        webSocket?.connect()
    }

    private fun startAudioThread(resultCode: Int, resultData: Intent?) {
        if (resultData == null) {
            Log.e(TAG, "No MediaProjection data")
            updateState(State.ERROR)
            return
        }

        val mediaProjection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData)

        // 初始化 Opus 编码器
        opusEncoder = OpusEncoder(SAMPLE_RATE, CHANNELS).apply { init() }

        // 初始化音频采集
        audioCapture = AudioCapture(mediaProjection, SAMPLE_RATE, CHANNELS)
        val record = audioCapture?.start() ?: run {
            Log.e(TAG, "Failed to start audio capture")
            updateState(State.ERROR)
            return
        }

        // 启动采集线程
        captureThread = Thread({
            Log.i(TAG, "Capture thread started")
            val pcmBuffer = ByteArray(PCM_FRAME_BYTES)
            var framesSent = 0L
            var bytesSent = 0L

            while (isRunning.get()) {
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) {
                    Thread.sleep(10)
                    continue
                }

                // 编码
                val encoded = opusEncoder?.encode(pcmBuffer.copyOf(bytesRead))
                if (encoded != null && encoded.isNotEmpty()) {
                    // 构造帧: [type:1][seq:2][data:N]
                    val seq = sequenceNumber++
                    val frame = ByteArray(3 + encoded.size)
                    frame[0] = 0x01  // audio frame type
                    frame[1] = ((seq shr 8) and 0xFF).toByte()
                    frame[2] = (seq and 0xFF).toByte()
                    System.arraycopy(encoded, 0, frame, 3, encoded.size)

                    webSocket?.sendAudioFrame(frame)
                    framesSent++
                    bytesSent += frame.size
                }
            }

            Log.i(TAG, "Capture thread stopped. Sent $framesSent frames, $bytesSent bytes")
            captureThread = null
        }, "AudioCapture").also { it.start() }
    }

    private fun stopCapture() {
        isRunning.set(false)
        captureThread?.join(3000)
        audioCapture?.stop()
        opusEncoder?.destroy()
        webSocket?.disconnect()
        webSocket = null
        updateState(State.IDLE)
    }

    private fun updateState(state: State) {
        currentState = state
        onStateChanged?.invoke(state)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "正在转发音频"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Relay")
            .setContentText("正在转发系统音频")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "停止", stopIntent)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
