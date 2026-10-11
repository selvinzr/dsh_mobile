package com.dsh.mobile.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** 语音识别状态。 */
sealed interface SpeechState {
    data object Idle : SpeechState
    data object Listening : SpeechState
    data object Transcribing : SpeechState
    data class Partial(val text: String) : SpeechState
    data class Result(val text: String) : SpeechState
    data class Error(val message: String) : SpeechState
}

/**
 * 一次语音听写会话（系统 SpeechRecognizer，厂商引擎，免费无 key）。
 *
 * 用法：start() 开始 → 波形通过 onAmplitude 回调 → stop() 结束并转写 → cancel() 放弃。
 * 会话用完必须 destroy()。
 */
class SpeechSession(private val context: Context) {

    companion object {
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    private var rmsCount = 0
    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    var onState: (SpeechState) -> Unit = {}
    var onAmplitude: (Float) -> Unit = {}

    fun start() {
        if (recognizer != null) return
        if (!isAvailable(context)) {
            onState(SpeechState.Error("此设备没有可用的语音识别服务"))
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        finished = false

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(SpeechState.Listening)
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onState(SpeechState.Transcribing)

            override fun onRmsChanged(rmsdB: Float) {
                rmsCount++
                if (rmsCount <= 30 || rmsCount % 50 == 0) {
                    android.util.Log.d("DshSpeech", "onRmsChanged #$rmsCount rms=$rmsdB")
                }
                // 引擎给的 dB 大致落在 -2..10，归一化到 0..1
                onAmplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                onState(
                    SpeechState.Error(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，再说一次"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                // 这个错误码不等于"本应用没有权限"：
                                // 小米等 ROM 的 ASR 服务对第三方 App 有限制时也会返回它。
                                // 先看自己到底有没有权限，再给出对应提示，避免误导用户。
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.RECORD_AUDIO,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    "系统语音服务拒绝了本次识别，正在改用系统语音输入…"
                                } else {
                                    "需要麦克风权限"
                                }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，稍后再试"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络异常，无法转写"
                            else -> "识别失败（$error）"
                        }
                    )
                )
                release()
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onState(if (text.isBlank()) SpeechState.Error("没有听清，再说一次") else SpeechState.Result(text))
                release()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onState(SpeechState.Partial(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        onState(SpeechState.Listening)
        runCatching { rec.startListening(intent) }
            .onFailure {
                finished = true
                onState(SpeechState.Error("无法启动语音识别：${it.message}"))
                release()
            }
    }

    /** 结束录音并等转写结果。 */
    fun stop() {
        onState(SpeechState.Transcribing)
        runCatching { recognizer?.stopListening() }
    }

    /** 放弃本次录音。 */
    fun cancel() {
        finished = true
        runCatching { recognizer?.cancel() }
        release()
        onState(SpeechState.Idle)
    }

    fun destroy() = cancel()

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
