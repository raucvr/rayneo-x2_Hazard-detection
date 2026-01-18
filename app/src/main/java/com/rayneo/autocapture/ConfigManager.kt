package com.rayneo.autocapture

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器 - 使用 SharedPreferences 存储配置
 */
object ConfigManager {
    private const val PREFS_NAME = "danger_detector_config"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_ENABLED = "enabled"

    private const val DEFAULT_INTERVAL = 3 // 默认 3 秒

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取 OpenRouter API Key
     */
    fun getApiKey(context: Context): String? {
        return getPrefs(context).getString(KEY_API_KEY, null)
    }

    /**
     * 设置 OpenRouter API Key
     */
    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    /**
     * 获取检测间隔（秒）
     */
    fun getInterval(context: Context): Int {
        return getPrefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
    }

    /**
     * 设置检测间隔（秒）
     */
    fun setInterval(context: Context, interval: Int) {
        getPrefs(context).edit().putInt(KEY_INTERVAL, interval).apply()
    }

    /**
     * 检查是否启用自动检测
     */
    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, false)
    }

    /**
     * 设置是否启用自动检测
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 检查配置是否完整（API Key 已设置）
     */
    fun isConfigured(context: Context): Boolean {
        val apiKey = getApiKey(context)
        return !apiKey.isNullOrBlank()
    }
}
