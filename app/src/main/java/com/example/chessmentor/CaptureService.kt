package com.example.chessmentor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var pieceRecognizer: PieceRecognizer? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val busy = AtomicBoolean(false)
    private var lastRun = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "chess_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channel, "Chess Mentor screen capture", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
            .setContentTitle("Chess Mentor").setContentText("Анализ экрана активен").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        else Notification.Builder(this).setContentTitle("Chess Mentor").setContentText("Анализ экрана активен").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        startForeground(31, notification)
        if (projection != null) return START_STICKY
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            // Android 14+ requires a callback to be registered before creating a VirtualDisplay.
            projection?.registerCallback(projectionCallback, Handler(mainLooper))
            startFrameCapture()
        }
        return START_STICKY
    }

    private fun startFrameCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width=metrics.widthPixels; val height=metrics.heightPixels; val density=metrics.densityDpi
        worker=HandlerThread("ChessFrameReader").also { it.start() }
        handler=Handler(worker!!.looper)
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        reader!!.setOnImageAvailableListener({ source ->
            val now=System.currentTimeMillis()
            if (now-lastRun<900 || !busy.compareAndSet(false,true)) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastRun=now
            val image=source.acquireLatestImage()
            if (image==null) { busy.set(false); return@setOnImageAvailableListener }
            try {
                val plane=image.planes[0]; val buffer=plane.buffer
                val pixelStride=plane.pixelStride; val rowStride=plane.rowStride
                val paddedWidth=rowStride/pixelStride
                val full=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buffer)
                val frame=Bitmap.createBitmap(full,0,0,width,height); full.recycle()
                val result=BoardVision.detect(frame)
                var message=result.message
                var fen:String?=null
                if(result.bounds!=null){
                    val crop=Bitmap.createBitmap(frame,result.bounds.left.coerceIn(0,frame.width-1),result.bounds.top.coerceIn(0,frame.height-1),result.bounds.side.coerceAtMost(frame.width-result.bounds.left).coerceAtMost(frame.height-result.bounds.top),result.bounds.side.coerceAtMost(frame.width-result.bounds.left).coerceAtMost(frame.height-result.bounds.top))
                    try {
                        val model=File(filesDir,"chess_pieces.tflite")
                        val prefs=getSharedPreferences("chessmentor",MODE_PRIVATE)
                        val whiteBottom=prefs.getBoolean("white_bottom",true)
                        val turn=if(prefs.getBoolean("black_to_move",false)) 'b' else 'w'
                        val recognizer = pieceRecognizer ?: PieceRecognizer(model).also { pieceRecognizer = it }
                        val recognized = recognizer.recognize(crop, whiteBottom)
                        fen = FenBuilder.complete(recognized.fenPlacement, turn)
                        message = "${recognized.message}. FEN сформирован; рокировки и en passant не определяются по одному кадру."
                    } catch(e:Exception){message=e.message?:"Не удалось распознать фигуры"} finally {crop.recycle()}
                }
                frame.recycle()
                sendBroadcast(Intent(ACTION_FRAME_RESULT).setPackage(packageName).putExtra("message",message).putExtra("fen",fen))
            } catch (e: Exception) {
                sendBroadcast(Intent(ACTION_FRAME_RESULT).setPackage(packageName).putExtra("message",e.message ?: "Ошибка анализа кадра"))
            } finally { image.close(); busy.set(false) }
        },handler)
        display=projection?.createVirtualDisplay("ChessMentorCapture",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
    }

    override fun onDestroy() {
        display?.release(); display=null
        reader?.close(); reader=null
        pieceRecognizer?.close(); pieceRecognizer=null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection=null
        worker?.quitSafely(); worker=null; handler=null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val ACTION_FRAME_RESULT="com.example.chessmentor.FRAME_RESULT" }
}
