package com.rayneo.autocapture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 警报管理器 - 播放危险警报音
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
        private const val ALERT_DURATION_MS = 500
        private const val ALERT_REPEAT_COUNT = 3
        private const val ALERT_INTERVAL_MS = 200L
    }

    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator", e)
        }
    }

    /**
     * 播放危险警报音 (3 次短促蜂鸣)
     */
    fun playDangerAlert() {
        Log.i(TAG, "Playing danger alert!")

        var count = 0
        val playNextTone = object : Runnable {
            override fun run() {
                if (count < ALERT_REPEAT_COUNT) {
                    try {
                        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ALERT_DURATION_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to play tone", e)
                    }
                    count++
                    mainHandler.postDelayed(this, ALERT_DURATION_MS + ALERT_INTERVAL_MS)
                }
            }
        }

        mainHandler.post(playNextTone)
    }

    /**
     * 播放单次提示音 (用于测试)
     */
    fun playTestTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play test tone", e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release ToneGenerator", e)
        }
    }
}
