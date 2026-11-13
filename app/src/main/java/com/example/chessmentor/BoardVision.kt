package com.example.chessmentor

import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Finds a square checkerboard in a screen frame. */
object BoardVision {
    data class Bounds(val left: Int, val top: Int, val side: Int, val score: Double)
    data class Detection(val bounds: Bounds?, val message: String)

    fun detect(bitmap: Bitmap): Detection {
        val scale = min(1.0, 420.0 / max(bitmap.width, bitmap.height))
        val small = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap, (bitmap.width*scale).toInt().coerceAtLeast(1), (bitmap.height*scale).toInt().coerceAtLeast(1), true) else bitmap
        val w=small.width; val h=small.height; val maxSide=min(w,h)
        if(maxSide<180){if(small!==bitmap)small.recycle();return Detection(null,"Кадр слишком мал")}
        var best:Bounds?=null
        val minSide=max(200,(maxSide*.42).toInt()); val stepSide=max(8,maxSide/90)
        var side=minSide-minSide%8
        while(side<=maxSide*.98){val cell=side/8;val posStep=max(4,cell/3);var y=0
            while(y+side<=h){var x=0;while(x+side<=w){val score=checkerScore(small,x,y,cell);if(best==null||score>best!!.score)best=Bounds(x,y,side,score);x+=posStep};y+=posStep};side+=stepSide}
        val found=best;val mapped=found?.let{Bounds((it.left/scale).toInt(),(it.top/scale).toInt(),(it.side/scale).toInt(),it.score)}
        if(small!==bitmap)small.recycle()
        return if(mapped!=null&&mapped.score>=13.0)Detection(mapped,"Доска обнаружена") else Detection(null,"Шахматная доска не обнаружена")
    }
    private fun checkerScore(b:Bitmap,x:Int,y:Int,cell:Int):Double{
        var lr=0.0;var lg=0.0;var lb=0.0;var dr=0.0;var dg=0.0;var db=0.0;var ln=0;var dn=0
        val offsets=arrayOf(.22 to .22,.78 to .22,.22 to .78,.78 to .78)
        for(r in 0..7)for(c in 0..7)for((ox,oy)in offsets){val color=b.getPixel((x+(c+ox)*cell).toInt().coerceIn(0,b.width-1),(y+(r+oy)*cell).toInt().coerceIn(0,b.height-1));if((r+c)%2==0){lr+=Color.red(color);lg+=Color.green(color);lb+=Color.blue(color);ln++}else{dr+=Color.red(color);dg+=Color.green(color);db+=Color.blue(color);dn++}}
        val a=lr/ln-dr/dn;val c=lg/ln-dg/dn;val d=lb/ln-db/dn;return kotlin.math.sqrt(a*a+c*c+d*d)
    }
}

/** YOLOv8 TFLite detector. Model classes: 0 board, 1..12 white/black pieces. */
class PieceRecognizer(private val modelFile: File) : AutoCloseable {
    data class Result(val fenPlacement:String,val confidence:Float,val pieces:Int,val message:String)
    private val interpreter: Interpreter
    init { require(modelFile.exists()) { "Модель не установлена. Нажми «Скачать модель распознавания»" }; interpreter=Interpreter(modelFile, Interpreter.Options().apply{setNumThreads(4)}) }

    fun recognize(board: Bitmap, whiteAtBottom:Boolean=true):Result {
        val input=letterbox(board)
        val shape=interpreter.getInputTensor(0).shape()
        require(shape.contentEquals(intArrayOf(1,640,640,3))) { "Неподдерживаемый формат входа модели: ${shape.contentToString()}" }
        val outputShape=interpreter.getOutputTensor(0).shape()
        val output=Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        interpreter.run(input,output)
        val rows=decode(output[0],outputShape)
        val boardPieces=Array(8){CharArray(8){'.'}}
        val scores=Array(8){FloatArray(8)}
        for(d in rows){
            val col=(d.cx/640f*8).toInt().coerceIn(0,7);val row=(d.cy/640f*8).toInt().coerceIn(0,7)
            if(d.confidence>scores[row][col]){scores[row][col]=d.confidence;boardPieces[row][col]=classToFen(d.classId)}
        }
        if(!whiteAtBottom){for(r in 0..3){val tmp=boardPieces[r];boardPieces[r]=boardPieces[7-r];boardPieces[7-r]=tmp};for(r in 0..7)boardPieces[r].reverse()}
        val all=boardPieces.sumOf{row->row.count{it!='.'}}
        val kingsW=boardPieces.sumOf{row->row.count{it=='K'}};val kingsB=boardPieces.sumOf{row->row.count{it=='k'}}
        val minConfidence=(scores.flatMap{it.toList()}.filter{it>0f}.minOrNull()?:0f)
        val placement=buildString{for(r in 0..7){var empty=0;for(c in 0..7){val p=boardPieces[r][c];if(p=='.')empty++ else{if(empty>0){append(empty);empty=0};append(p)}};if(empty>0)append(empty);if(r<7)append('/') }}
        val validation=validatePlacement(boardPieces)
        if(kingsW!=1||kingsB!=1)throw IllegalStateException("Распознавание сомнительно: должно быть ровно по одному королю каждого цвета (найдено $kingsW/$kingsB).")
        if(all !in 2..32)throw IllegalStateException("Невозможное число фигур: $all. Попробуй изменить масштаб доски.")
        if(!validation)throw IllegalStateException("Распознанная расстановка не прошла базовую проверку шахматной позиции.")
        if(minConfidence<.45f)throw IllegalStateException("Низкая уверенность распознавания (${"%.0f".format(minConfidence*100)}%). Позиция не передана движку.")
        return Result(placement,minConfidence,all,"Распознано фигур: $all; минимальная уверенность: ${"%.0f".format(minConfidence*100)}%")
    }
    private data class Det(val cx:Float,val cy:Float,val w:Float,val h:Float,val confidence:Float,val classId:Int)
    private fun decode(out:Array<FloatArray>,shape:IntArray):List<Det>{
        // YOLOv8 export commonly yields [1,17,8400]; also accept [1,8400,17].
        val channelsFirst=shape[1]==17
        val count=if(channelsFirst)shape[2] else shape[1]
        val candidates=ArrayList<Det>()
        for(i in 0 until count){fun v(k:Int)=if(channelsFirst)out[k][i] else out[i][k]
            val cx=v(0);val cy=v(1);val w=v(2);val h=v(3);var best=0f;var cls=-1
            for(k in 4..16){val s=v(k);if(s>best){best=s;cls=k-4}}
            if(best>=.35f&&cls in 1..12&&w>2&&h>2)candidates.add(Det(cx,cy,w,h,best,cls))
        }
        // Non-maximum suppression, class-aware.
        val kept=ArrayList<Det>()
        for(d in candidates.sortedByDescending{it.confidence}){if(kept.none{it.classId==d.classId&&iou(it,d)>.45f})kept.add(d)}
        return kept
    }
    private fun iou(a:Det,b:Det):Float{val l=max(a.cx-a.w/2,b.cx-b.w/2);val t=max(a.cy-a.h/2,b.cy-b.h/2);val r=min(a.cx+a.w/2,b.cx+b.w/2);val bot=min(a.cy+a.h/2,b.cy+b.h/2);val inter=max(0f,r-l)*max(0f,bot-t);return inter/(a.w*a.h+b.w*b.h-inter+1e-6f)}
    private fun classToFen(id:Int):Char=when(id){1->'K';2->'Q';3->'R';4->'B';5->'N';6->'P';7->'k';8->'q';9->'r';10->'b';11->'n';12->'p';else->'.'}
    private fun letterbox(src:Bitmap):ByteBuffer{val size=640;val scale=min(size.toFloat()/src.width,size.toFloat()/src.height);val rw=(src.width*scale).toInt();val rh=(src.height*scale).toInt();val resized=Bitmap.createScaledBitmap(src,rw,rh,true);val canvas=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val c=android.graphics.Canvas(canvas);c.drawColor(Color.rgb(114,114,114));c.drawBitmap(resized,(size-rw)/2f,(size-rh)/2f,null);resized.recycle();val b=ByteBuffer.allocateDirect(1*size*size*3*4).order(ByteOrder.nativeOrder());for(y in 0 until size)for(x in 0 until size){val p=canvas.getPixel(x,y);b.putFloat(Color.red(p)/255f);b.putFloat(Color.green(p)/255f);b.putFloat(Color.blue(p)/255f)};canvas.recycle();b.rewind();return b}
    private fun validatePlacement(b:Array<CharArray>):Boolean{
        for(c in 0..7)if(b[0][c].lowercaseChar()=='p'||b[7][c].lowercaseChar()=='p')return false
        val wk=locateKing(b,'K')?:return false;val bk=locateKing(b,'k')?:return false
        if(kotlin.math.abs(wk.first-bk.first)<=1&&kotlin.math.abs(wk.second-bk.second)<=1)return false
        return true
    }
    private fun locateKing(b:Array<CharArray>,king:Char):Pair<Int,Int>?{for(r in 0..7)for(c in 0..7)if(b[r][c]==king)return r to c;return null}
    override fun close(){interpreter.close()}
}

object FenBuilder {
    fun complete(placement:String,sideToMove:Char='w'):String { require(sideToMove=='w'||sideToMove=='b');return "$placement $sideToMove - - 0 1" }
}
