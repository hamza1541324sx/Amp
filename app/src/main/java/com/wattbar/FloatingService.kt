package com.wattbar

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var pillView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            updatePill()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        createPill()
        handler.post(updateRunnable)
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            "wattbar_ch", "WattBar", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "WattBar overlay service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "wattbar_ch")
            .setContentTitle("WattBar")
            .setContentText("Showing power overlay — tap to stop")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createPill() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pillView = TextView(this).apply {
            text = "⚡ -- W"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(28, 10, 28, 10)
            background = makePillBg(Color.parseColor("#00FF88"))
            elevation = 12f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 36
        }
        windowManager.addView(pillView, params)
        makeDraggable(pillView, params)
    }

    private fun makePillBg(borderColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 60f
            setColor(Color.parseColor("#D9000000"))
            setStroke(3, borderColor)
        }
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePill() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val currentMicro = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val voltageMv = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) (level * 100) / scale else 0
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val currentA = Math.abs(currentMicro) / 1_000_000.0
        val voltageV = voltageMv / 1000.0
        val watts = currentA * voltageV
        val arrow = if (isCharging) "⚡" else "🔋"
        val display = "$arrow ${"%.1f".format(watts)}W  $pct%"
        val (textColor, borderColor) = when {
            pct <= 15 && !isCharging -> Pair(Color.parseColor("#FF4466"), Color.parseColor("#FF4466"))
            isCharging -> Pair(Color.parseColor("#00FF88"), Color.parseColor("#00FF88"))
            pct <= 30 -> Pair(Color.parseColor("#FFCC00"), Color.parseColor("#FFCC00"))
            else -> Pair(Color.WHITE, Color.parseColor("#00CCFF"))
        }
        handler.post {
            pillView.text = display
            pillView.setTextColor(textColor)
            pillView.background = makePillBg(borderColor)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::pillView.isInitialized) {
            try { windowManager.removeView(pillView) } catch (_: Exception) {}
        }
    }
}
