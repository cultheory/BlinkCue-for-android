package com.example.blinkcue

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var timeoutEditText: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusTextView: TextView

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BlinkForegroundService.ACTION_STATE_CHANGED) {
                return
            }
            val isRunning = intent.getBooleanExtra(
                BlinkForegroundService.EXTRA_IS_RUNNING,
                BlinkForegroundService.isRunning()
            )
            updateUi(isRunning)
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                continueStartFlow()
            } else {
                showToast(R.string.camera_permission_required)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                continueStartFlow()
            } else {
                showToast(R.string.notification_permission_required)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timeoutEditText = findViewById(R.id.timeoutEditText)
        toggleButton = findViewById(R.id.toggleButton)
        statusTextView = findViewById(R.id.statusTextView)

        timeoutEditText.setText(loadTimeoutSeconds().toString())
        updateUi(BlinkForegroundService.isRunning())

        toggleButton.setOnClickListener {
            if (BlinkForegroundService.isRunning()) {
                stopBlinkService()
            } else {
                val timeoutSeconds = parseTimeoutSeconds() ?: return@setOnClickListener
                saveTimeoutSeconds(timeoutSeconds)
                requestPermissionsAndStart(timeoutSeconds)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BlinkForegroundService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateUi(BlinkForegroundService.isRunning())
    }

    private fun requestPermissionsAndStart(timeoutSeconds: Int) {
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showToast(R.string.overlay_permission_required)
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        startBlinkService(timeoutSeconds)
    }

    private fun continueStartFlow() {
        val timeoutSeconds = parseTimeoutSeconds() ?: return
        saveTimeoutSeconds(timeoutSeconds)
        requestPermissionsAndStart(timeoutSeconds)
    }

    private fun startBlinkService(timeoutSeconds: Int) {
        val intent = Intent(this, BlinkForegroundService::class.java).apply {
            action = BlinkForegroundService.ACTION_START
            putExtra(BlinkForegroundService.EXTRA_TIMEOUT_MS, timeoutSeconds * 1000L)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUi(true)
    }

    private fun stopBlinkService() {
        val intent = Intent(this, BlinkForegroundService::class.java).apply {
            action = BlinkForegroundService.ACTION_STOP
        }
        startService(intent)
        updateUi(false)
    }

    private fun hasCameraPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseTimeoutSeconds(): Int? {
        val seconds = timeoutEditText.text.toString().trim().toIntOrNull()
        if (seconds == null || seconds !in 1..60) {
            showToast(R.string.timeout_range_error)
            return null
        }
        return seconds
    }

    private fun saveTimeoutSeconds(timeoutSeconds: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_TIMEOUT_SECONDS, timeoutSeconds)
            .apply()
    }

    private fun loadTimeoutSeconds(): Int {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun updateUi(isRunning: Boolean) {
        statusTextView.text = getString(if (isRunning) R.string.status_running else R.string.status_stopped)
        statusTextView.setTextColor(
            android.graphics.Color.parseColor(if (isRunning) "#18A76C" else "#8A8A8A")
        )
        toggleButton.text = getString(if (isRunning) R.string.stop else R.string.start)
        toggleButton.backgroundTintList = ColorStateList.valueOf(
            if (isRunning) getColor(R.color.mint_dark) else android.graphics.Color.parseColor("#111111")
        )
        toggleButton.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
    }

    companion object {
        private const val PREFS_NAME = "blinkcue_prefs"
        private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        private const val DEFAULT_TIMEOUT_SECONDS = 4
    }
}
