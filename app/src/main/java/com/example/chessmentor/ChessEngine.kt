package com.example.chessmentor

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/** UCI adapter for a Stockfish Android binary placed at filesDir/stockfish. */
class ChessEngine(private val binary: File) {
    data class Result(val bestMove: String, val ponder: String?, val score: String, val depth: Int, val pv: String)

    fun analyze(fen: String, elo: Int = 2000, depth: Int = 16, timeoutSeconds: Long = 25): Result {
        val fields = fen.trim().split(Regex("\\s+"))
        require(fields.size == 6) { "FEN должна содержать 6 полей" }
        require(fields[1] == "w" || fields[1] == "b") { "Некорректная сторона хода в FEN" }
        require(fields[2].matches(Regex("(?:-|K?Q?k?q?)"))) { "Некорректные права рокировки в FEN" }
        require(fields[3].matches(Regex("(?:-|[a-h][36])"))) { "Некорректное поле en passant в FEN" }
        require(fields[4].toIntOrNull() != null && fields[4].toInt() >= 0) { "Некорректный счётчик полуходов в FEN" }
        require(fields[5].toIntOrNull() != null && fields[5].toInt() >= 1) { "Некорректный номер хода в FEN" }
        require(binary.exists() && binary.canExecute()) { "Stockfish не установлен. См. README_RU.md" }
        val process = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        val writer = OutputStreamWriter(process.outputStream)
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        fun send(s: String) { writer.write(s + "\n"); writer.flush() }
        fun waitFor(token: String, timeout: Long = 8): String {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)
            val lines = StringBuilder()
            while (System.nanoTime() < end) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    lines.append(line).append('\n')
                    if (line.startsWith(token)) return lines.toString()
                } else Thread.sleep(20)
            }
            throw IllegalStateException("Stockfish не ответил: $token")
        }
        try {
            send("uci"); waitFor("uciok")
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value ${elo.coerceIn(1320, 3190)}")
            send("isready"); waitFor("readyok")
            send("position fen $fen")
            send("go depth ${depth.coerceIn(8, 24)}")
            val output = waitFor("bestmove", timeoutSeconds)
            val best = output.lineSequence().last { it.startsWith("bestmove") }.split(' ')
            val info = output.lineSequence().filter { it.startsWith("info ") && it.contains(" pv ") }.lastOrNull().orEmpty()
            val scoreMatch = Regex("score (cp|mate) (-?\\d+)").find(info)
            val scoreKind = scoreMatch?.groupValues?.getOrNull(1)
            val scoreValue = scoreMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
            val score = if (scoreKind == "mate" && scoreValue != null) "мат в $scoreValue"
                else if (scoreKind == "cp" && scoreValue != null) "оценка ${"%.2f".format(java.util.Locale.US, scoreValue / 100.0)}"
                else "оценка не получена"
            val d = Regex("\\bdepth (\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pv = Regex("\\bpv (.+)$").find(info)?.groupValues?.get(1).orEmpty()
            val ponder = best.indexOf("ponder").takeIf { it >= 0 }?.let { best.getOrNull(it + 1) }
            return Result(best.getOrElse(1) { "(none)" }, ponder, score, d, pv)
        } finally {
            runCatching { send("quit") }
            runCatching { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
        }
    }
}
