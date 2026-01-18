package com.rayneo.autocapture

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 配置界面 - 设置 API Key 和检测参数
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var editApiKey: EditText
    private lateinit var seekInterval: SeekBar
    private lateinit var textInterval: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnTest: Button

    private var alertManager: AlertManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        initViews()
        loadConfig()
        setupListeners()
        updateStatus()

        alertManager = AlertManager(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        alertManager?.release()
        super.onDestroy()
    }

    private fun initViews() {
        editApiKey = findViewById(R.id.editApiKey)
        seekInterval = findViewById(R.id.seekInterval)
        textInterval = findViewById(R.id.textInterval)
        textStatus = findViewById(R.id.textStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnTest = findViewById(R.id.btnTest)
    }

    private fun loadConfig() {
        // 加载 API Key
        val apiKey = ConfigManager.getApiKey(this)
        if (!apiKey.isNullOrBlank()) {
            editApiKey.setText(apiKey)
        }

        // 加载检测间隔
        val interval = ConfigManager.getInterval(this)
        seekInterval.progress = interval
        textInterval.text = "$interval sec"
    }

    private fun setupListeners() {
        // 间隔滑块
        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val interval = progress.coerceAtLeast(2)
                textInterval.text = "$interval sec"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val interval = seekBar?.progress?.coerceAtLeast(2) ?: 3
                ConfigManager.setInterval(this@ConfigActivity, interval)
            }
        })

        // 启动按钮
        btnStart.setOnClickListener {
            saveConfig()
            startDetection()
        }

        // 停止按钮
        btnStop.setOnClickListener {
            stopDetection()
        }

        // 测试按钮
        btnTest.setOnClickListener {
            alertManager?.playDangerAlert()
            Toast.makeText(this, "Playing test alert", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveConfig() {
        val apiKey = editApiKey.text.toString().trim()
        if (apiKey.isNotBlank()) {
            ConfigManager.setApiKey(this, apiKey)
        }

        val interval = seekInterval.progress.coerceAtLeast(2)
        ConfigManager.setInterval(this, interval)
    }

    private fun startDetection() {
        val apiKey = editApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存配置
        ConfigManager.setApiKey(this, apiKey)

        // 启动服务
        val intent = Intent(this, AutoCaptureService::class.java).apply {
            action = AutoCaptureService.ACTION_START_CONTINUOUS
        }
        startForegroundService(intent)

        Toast.makeText(this, "Danger detection started", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun stopDetection() {
        val intent = Intent(this, AutoCaptureService::class.java).apply {
            action = AutoCaptureService.ACTION_STOP_CONTINUOUS
        }
        startService(intent)

        Toast.makeText(this, "Danger detection stopped", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val isEnabled = ConfigManager.isEnabled(this)
        val isConfigured = ConfigManager.isConfigured(this)

        textStatus.text = when {
            isEnabled -> "Status: Running"
            !isConfigured -> "Status: Not configured"
            else -> "Status: Stopped"
        }

        textStatus.setTextColor(
            if (isEnabled) 0xFF4CAF50.toInt() else 0xFFff6b6b.toInt()
        )
    }
}
