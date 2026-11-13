package com.example.chessmentor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.io.File
import java.net.URL
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val captureRequest = 4102
    private lateinit var status: TextView
    private lateinit var fenInput: EditText
    private lateinit var goalPicker: Spinner
    private lateinit var turnPicker: Spinner
    private lateinit var orientationPicker: Spinner
    private var lastAnalyzedFen: String? = null
    private val frameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("message")?.let { status.text = "Экран: $it" }
            intent?.getStringExtra("fen")?.let { recognizedFen ->
                fenInput.setText(recognizedFen)
                if (recognizedFen != lastAnalyzedFen) {
                    lastAnalyzedFen = recognizedFen
                    analyzePosition()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24); setBackgroundColor(0xFF101827.toInt()) }
        fun label(t: String, size: Float = 15f) = TextView(this).apply { text=t; textSize=size; setTextColor(0xFFF3F4F6.toInt()); setPadding(0, 8, 0, 8) }
        root.addView(label("CHESS MENTOR", 25f))
        root.addView(label("Тренировочный помощник для партий против ботов Chess.com. Анализ выполняется локальным Stockfish."))
        root.addView(label("Цель анализа"))
        goalPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TrainingGoal.values().map { it.title })
        }
        root.addView(goalPicker)
        root.addView(label("Сторона, которая ходит (по умолчанию белые)"))
        turnPicker = Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Ход белых","Ход чёрных")); onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){getSharedPreferences("chessmentor",MODE_PRIVATE).edit().putBoolean("black_to_move",pos==1).apply()}} }
        root.addView(turnPicker)
        root.addView(label("Ориентация доски"))
        orientationPicker = Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Белые снизу","Чёрные снизу")); onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){getSharedPreferences("chessmentor",MODE_PRIVATE).edit().putBoolean("white_bottom",pos==0).apply()}} }
        root.addView(orientationPicker)
        root.addView(Button(this).apply { text="Скачать модель распознавания (около 52 МБ)"; setOnClickListener { downloadRecognitionModel() } })
        root.addView(label("FEN позиции (заполнится после распознавания)"))
        fenInput = EditText(this).apply {
            setText("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFAAAAAA.toInt()); textSize=12f; setSingleLine(false)
            setBackgroundColor(0xFF263449.toInt())
        }
        root.addView(fenInput)
        status = label("Статус: готов к настройке")
        root.addView(status)
        root.addView(Button(this).apply { text="1. Разрешить показ поверх приложений"; setOnClickListener { requestOverlayPermission() } })
        root.addView(Button(this).apply { text="2. Разрешить захват экрана"; setOnClickListener { requestScreenCapture() } })
        root.addView(Button(this).apply { text="3. Запустить плавающее окно"; setOnClickListener { startOverlay() } })
        root.addView(Button(this).apply { text="Анализировать FEN движком (2000 Elo)"; setOnClickListener { analyzePosition() } })
        root.addView(Button(this).apply { text="Остановить плавающее окно"; setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)); status.text="Окно остановлено" } })
        root.addView(Button(this).apply { text="Начать автоматическое обнаружение доски"; setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) { status.text="Сначала разреши показ поверх приложений."; return@setOnClickListener }
            startService(Intent(this@MainActivity, OverlayService::class.java))
            status.text="Запущен захват. Открой Chess.com и покажи всю доску на экране."
        } })
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(frameReceiver, IntentFilter(CaptureService.ACTION_FRAME_RESULT), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(frameReceiver, IntentFilter(CaptureService.ACTION_FRAME_RESULT))
        refreshStatus()
    }

    private fun downloadRecognitionModel() {
        status.text="Загрузка модели распознавания…"
        thread {
            try {
                val target=File(filesDir,"chess_pieces.tflite")
                val connection=URL("https://huggingface.co/NAKSTStudio/yolov8m-chess-piece-detection/resolve/main/best_f16.tflite").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout=20000; connection.readTimeout=60000; connection.instanceFollowRedirects=true
                connection.connect()
                if(connection.responseCode !in 200..299) error("Сервер модели вернул HTTP ${connection.responseCode}")
                connection.inputStream.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                if(target.length()<1_000_000) error("Файл модели выглядит неполным")
                runOnUiThread { status.text="Модель загружена (${target.length()/1024/1024} МБ). Можно запускать распознавание." }
            } catch(e:Exception){runOnUiThread{status.text="Ошибка загрузки модели: ${e.message}"}}
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) else refreshStatus()
    }
    private fun requestScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
    }
    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("data", data))
            status.text = "Захват экрана разрешён. Android показывает системный индикатор."
        }
    }
    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) { status.text="Сначала разреши показ поверх приложений."; return }
        startService(Intent(this, OverlayService::class.java)); status.text="Плавающее окно запущено."
    }
    private fun analyzePosition() {
        val goal = TrainingGoal.values()[goalPicker.selectedItemPosition]
        val fen = fenInput.text.toString().trim()
        status.text = "Stockfish анализирует позицию…"
        thread {
            try {
                val result = ChessEngine(File(filesDir, "stockfish")).analyze(fen)
                val hint = HintComposer.compose(goal, result)
                runOnUiThread {
                    status.text = hint
                    startService(Intent(this, OverlayService::class.java).putExtra("hint", hint))
                }
            } catch (e: Exception) { runOnUiThread { status.text = e.message ?: "Ошибка анализа" } }
        }
    }
    private fun refreshStatus() { status.text = if (Settings.canDrawOverlays(this)) "Разрешение поверх приложений выдано" else "Нужно выдать разрешения Android" }
    override fun onDestroy() { runCatching { unregisterReceiver(frameReceiver) }; super.onDestroy() }
}
