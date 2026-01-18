package com.rayneo.autocapture

import android.app.Application
import android.util.Log

class AutoCaptureApp : Application() {

    companion object {
        const val TAG = "AutoCapture"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AutoCaptureApp initialized")

        // 初始化 MercurySDK (如果需要)
        try {
            val mercurySDKClass = Class.forName("com.ffalcon.mercury.android.sdk.MercurySDK")
            val initMethod = mercurySDKClass.getMethod("init", Application::class.java)
            initMethod.invoke(null, this)
            Log.i(TAG, "MercurySDK initialized successfully")
        } catch (e: Exception) {
            Log.w(TAG, "MercurySDK not available or init failed: ${e.message}")
        }
    }
}
