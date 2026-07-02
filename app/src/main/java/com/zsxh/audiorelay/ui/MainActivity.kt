package com.zsxh.audiorelay.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zsxh.audiorelay.databinding.ActivityMainBinding
import com.zsxh.audiorelay.service.AudioCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: AudioCaptureService? = null
    private var isBound = false
    private var pendingRoomId = ""
    private var pendingPassword = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AudioCaptureService.LocalBinder).service
            isBound = true
            service?.onStateChanged = { state -> runOnUiThread { updateUI(state) } }
            updateUI(service?.currentState ?: AudioCaptureService.State.IDLE)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "需要屏幕录制权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "需要录音和通知权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 加载保存的设置
        val prefs = getSharedPreferences("audio_relay", Context.MODE_PRIVATE)
        binding.etRoomId.setText(prefs.getString("room_id", "default"))
        binding.etServerUrl.setText(prefs.getString("server_url", "wss://audio.zsxh.me/ws"))
        binding.etPassword.setText(prefs.getString("password", ""))

        binding.btnStart.setOnClickListener {
            if (service?.currentState == AudioCaptureService.State.STREAMING ||
                service?.currentState == AudioCaptureService.State.CONNECTING) {
                stopCapture()
            } else {
                startCapture()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AudioCaptureService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun startCapture() {
        val roomId = binding.etRoomId.text.toString().trim()
        if (roomId.isEmpty()) {
            binding.etRoomId.error = "请输入房间 ID"
            return
        }

        // 保存设置
        getSharedPreferences("audio_relay", Context.MODE_PRIVATE).edit().apply {
            putString("room_id", roomId)
            putString("server_url", binding.etServerUrl.text.toString().trim())
            putString("password", binding.etPassword.text.toString().trim())
            apply()
        }

        pendingRoomId = roomId
        pendingPassword = binding.etPassword.text.toString().trim()

        // 检查权限
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(AudioCaptureService.EXTRA_ROOM_ID, pendingRoomId)
            putExtra(AudioCaptureService.EXTRA_PASSWORD, pendingPassword)
            putExtra(AudioCaptureService.EXTRA_SERVER_URL, binding.etServerUrl.text.toString().trim())
        }
        startForegroundService(intent)
    }

    private fun stopCapture() {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUI(state: AudioCaptureService.State) {
        when (state) {
            AudioCaptureService.State.IDLE -> {
                binding.tvStatus.text = "未连接"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                binding.btnStart.text = "开始转发"
                binding.btnStart.isEnabled = true
                setInputsEnabled(true)
            }
            AudioCaptureService.State.CONNECTING -> {
                binding.tvStatus.text = "连接中..."
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.btnStart.text = "停止"
                binding.btnStart.isEnabled = true
                setInputsEnabled(false)
            }
            AudioCaptureService.State.CONNECTED -> {
                binding.tvStatus.text = "已连接"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
            AudioCaptureService.State.STREAMING -> {
                binding.tvStatus.text = "🎙️ 正在转发音频"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                binding.btnStart.text = "停止转发"
                binding.btnStart.isEnabled = true
                setInputsEnabled(false)
            }
            AudioCaptureService.State.ERROR -> {
                binding.tvStatus.text = "错误"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.btnStart.text = "重试"
                binding.btnStart.isEnabled = true
                setInputsEnabled(true)
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.etRoomId.isEnabled = enabled
        binding.etServerUrl.isEnabled = enabled
        binding.etPassword.isEnabled = enabled
    }
}
