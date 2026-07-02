package com.zsxh.audiorelay.audio

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.zsxh.audiorelay.opus.ConcentusAdapter

/**
 * Opus 编码器 — 基于 Concentus (纯 Java Opus 实现)
 */
class OpusEncoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val bitrate: Int = 64000
) {
    companion object {
        private const val TAG = "OpusEncoder"
        const val FRAME_SIZE = 960  // 20ms @ 48kHz
        const val MAX_PACKET_SIZE = 4000
    }

    private var encoder: ConcentusAdapter? = null

    fun init() {
        try {
            encoder = ConcentusAdapter(sampleRate, channels, bitrate)
            Log.i(TAG, "Opus encoder initialized: ${sampleRate}Hz, ${channels}ch, ${bitrate}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Opus encoder: ${e.message}")
            encoder = null
        }
    }

    /**
     * 编码 PCM 16-bit LE 数据为 Opus
     * @return Opus 编码数据，失败返回 null
     */
    fun encode(pcmData: ByteArray): ByteArray? {
        return encoder?.encode(pcmData)
    }

    fun destroy() {
        encoder = null
    }
}

/**
 * 系统音频采集器 — 使用 AudioPlaybackCapture (Android 10+)
 */
class AudioCapture(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = 48000,
    private val channels: Int = 1
) {
    companion object {
        private const val TAG = "AudioCapture"
    }

    private var audioRecord: AudioRecord? = null
    private val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT) * 2

    fun start(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()

        record.startRecording()
        audioRecord = record
        Log.i(TAG, "Audio capture started: ${sampleRate}Hz, ${channels}ch, buf=$bufferSize")
        return record
    }

    fun read(buffer: ByteArray): Int {
        return audioRecord?.read(buffer, 0, buffer.size) ?: -1
    }

    fun stop() {
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
            }
        }
        audioRecord = null
    }
}
