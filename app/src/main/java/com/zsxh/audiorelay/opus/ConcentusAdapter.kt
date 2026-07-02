package com.zsxh.audiorelay.opus

import android.util.Log
import org.concentus.OpusApplication
import org.concentus.OpusEncoder as ConcentusEncoder
import org.concentus.OpusSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Concentus Opus 编码器适配器
 * 纯 Java 实现，无需 NDK
 */
class ConcentusAdapter(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val bitrate: Int = 64000
) {
    companion object {
        private const val TAG = "ConcentusAdapter"
        private const val FRAME_SIZE = 960  // 20ms @ 48kHz
        private const val MAX_PACKET_SIZE = 4000
    }

    private val encoder: ConcentusEncoder

    init {
        encoder = ConcentusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
        encoder.setBitrate(bitrate)
        encoder.setSignal(OpusSignal.OPUS_SIGNAL_MUSIC)
        Log.i(TAG, "Concentus Opus encoder created")
    }

    /**
     * 编码 PCM 16-bit LE 数据为 Opus
     */
    fun encode(pcmData: ByteArray): ByteArray? {
        try {
            // 转换 ByteArray → ShortArray (PCM 16-bit LE)
            val sampleCount = pcmData.size / 2
            val pcmShort = ShortArray(sampleCount)
            val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                pcmShort[i] = buffer.short
            }

            // 如果采样数不足一个完整帧，补齐
            val paddedPcm: ShortArray
            if (sampleCount < FRAME_SIZE) {
                paddedPcm = ShortArray(FRAME_SIZE)
                System.arraycopy(pcmShort, 0, paddedPcm, 0, sampleCount)
            } else {
                paddedPcm = pcmShort
            }

            // 编码
            val output = ByteArray(MAX_PACKET_SIZE)
            val encodedSize = encoder.encode(paddedPcm, 0, FRAME_SIZE, output, 0, MAX_PACKET_SIZE)

            return if (encodedSize > 0) {
                output.copyOf(encodedSize)
            } else {
                Log.w(TAG, "Opus encode returned $encodedSize")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode error: ${e.message}")
            return null
        }
    }
}
