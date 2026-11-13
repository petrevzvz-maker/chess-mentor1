package com.example.chessmentor

import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val frameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("message")?.let { bubble?.text = "♟ Chess Mentor\n$it" }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "♟ Chess Mentor\nВыбери цель и запусти анализ"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xEE172033.toInt())
            setPadding(18, 14, 18, 14)
            elevation = 12f
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 170 }
        view.setOnTouchListener(object : View.OnTouchListener {
            var downX=0f; var downY=0f; var startX=0; var startY=0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; startX=p.x; startY=p.y; return true }
                    MotionEvent.ACTION_MOVE -> { p.x=startX-(e.rawX-downX).toInt(); p.y=startY+(e.rawY-downY).toInt(); wm.updateViewLayout(view,p); return true }
                }
                return false
            }
        })
        wm.addView(view,p); bubble=view; params=p
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(frameReceiver, IntentFilter(CaptureService.ACTION_FRAME_RESULT), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(frameReceiver, IntentFilter(CaptureService.ACTION_FRAME_RESULT))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("hint")?.let { bubble?.text = "♟ Chess Mentor\n$it" }
        return START_STICKY
    }
    override fun onDestroy() { runCatching { unregisterReceiver(frameReceiver) }; bubble?.let { wm.removeView(it) }; bubble=null; params=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
