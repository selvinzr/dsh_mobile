package com.dsh.mobile.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * 自己采集麦克风音量来驱动波形。
 *
 * 背景：小米等厂商的 SpeechRecognizer 引擎**不实现 onRmsChanged**（实测 0 次回调），
 * 所以不能依赖识别引擎给音量，改为自己开一路 AudioRecord 读 PCM 算 RMS。
 *
 * 若语音识别已独占麦克风导致读到全 0，回调会连续报 0，UI 侧据此退化为「录音中」脉冲动画。
 */
class MicLevelMeter {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** 启动音量采集；onLevel 回调 0..1。返回是否成功启动。 */
    @SuppressLint("MissingPermission")
    fun start(onLevel: (Float) -> Unit): Boolean {
        if (running) return true
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val bufSize = minBuf.coerceAtLeast(sampleRate / 10 * 2) // ≥100ms

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord 创建失败: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            Log.w(TAG, "AudioRecord 未初始化（麦克风可能被识别引擎独占）")
            return false
        }

        record = rec
        running = true
        val buf = ShortArray(bufSize / 2)

        thread = Thread {
            val started = runCatching { rec.startRecording() }.isSuccess
            Log.d(TAG, "meter thread started=$started")
            var smoothed = 0f
            var frames = 0
            var peak = 0f
            while (running) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    Log.w(TAG, "read 异常: ${e.message}")
                    -1
                }
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / n).toFloat()
                // 用 dB 尺度映射，符合人耳/话筒的动态范围：
                // 静音约 -60dB，正常说话约 -30..-15dB
                val db = 20f * kotlin.math.log10(rms.coerceAtLeast(1e-7f))
                val target = ((db + 58f) / 43f).coerceIn(0f, 1f)
                // 起音快、回落慢，避免波形抖成噪点
                smoothed = if (target > smoothed) {
                    smoothed * 0.35f + target * 0.65f
                } else {
                    smoothed * 0.75f + target * 0.25f
                }
                frames++
                if (smoothed > peak) peak = smoothed
                if (frames % 20 == 0) {
                    Log.d(TAG, "mic db=$db level=$smoothed peak=$peak")
                }
                onLevel(smoothed)
            }
            runCatching { rec.stop() }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(400)
        thread = null
        runCatching { record?.release() }
        record = null
    }

    private companion object {
        const val TAG = "DshMic"
    }
}
