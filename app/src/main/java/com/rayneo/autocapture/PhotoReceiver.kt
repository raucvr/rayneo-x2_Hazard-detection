package com.rayneo.autocapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 接收 ADB 广播命令的接收器
 *
 * 使用方法:
 *   adb shell am broadcast -a com.rayneo.autocapture.TAKE_PHOTO
 *   adb shell am broadcast -a com.rayneo.autocapture.START_SERVICE
 *   adb shell am broadcast -a com.rayneo.autocapture.STOP_SERVICE
 */
class PhotoReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "PhotoReceiver"
        const val ACTION_TAKE_PHOTO = "com.rayneo.autocapture.TAKE_PHOTO"
        const val ACTION_START_SERVICE = "com.rayneo.autocapture.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.rayneo.autocapture.STOP_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received broadcast: ${intent.action}")

        when (intent.action) {
            ACTION_TAKE_PHOTO -> {
                // 拍照命令
                val serviceIntent = Intent(context, AutoCaptureService::class.java).apply {
                    action = AutoCaptureService.ACTION_CAPTURE
                }
                startServiceSafely(context, serviceIntent)
            }

            ACTION_START_SERVICE -> {
                // 启动服务
                val serviceIntent = Intent(context, AutoCaptureService::class.java).apply {
                    action = AutoCaptureService.ACTION_START
                }
                startServiceSafely(context, serviceIntent)
            }

            ACTION_STOP_SERVICE -> {
                // 停止服务
                val serviceIntent = Intent(context, AutoCaptureService::class.java).apply {
                    action = AutoCaptureService.ACTION_STOP
                }
                context.startService(serviceIntent)
            }
        }
    }

    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
        }
    }
}
