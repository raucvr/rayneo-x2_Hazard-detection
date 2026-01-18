package com.rayneo.autocapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启动接收器
 *
 * 在设备启动完成后自动启动危险检测服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed received")

            // 检查是否已配置且启用
            val isConfigured = ConfigManager.isConfigured(context)
            val isEnabled = ConfigManager.isEnabled(context)

            Log.i(TAG, "isConfigured: $isConfigured, isEnabled: $isEnabled")

            if (isConfigured && isEnabled) {
                Log.i(TAG, "Starting danger detection service on boot")

                try {
                    val serviceIntent = Intent(context, AutoCaptureService::class.java).apply {
                        action = AutoCaptureService.ACTION_START_CONTINUOUS
                    }
                    context.startForegroundService(serviceIntent)
                    Log.i(TAG, "Service started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot", e)
                }
            } else {
                Log.i(TAG, "Not starting service: not configured or not enabled")
            }
        }
    }
}
