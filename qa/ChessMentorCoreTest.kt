import com.example.chessmentor.*
import java.io.File
fun main() {
  val engine = ChessEngine(File("/tmp/fake-stockfish"))
  val result = engine.analyze("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
  check(result.bestMove == "e2e4")
  check(result.ponder == "e7e5")
  check(result.depth == 16)
  check(result.score == "оценка 0.42")
  check(HintComposer.compose(TrainingGoal.UNIVERSAL,result).contains("e2e4"))
  check(HintComposer.compose(TrainingGoal.BRILLIANT,result).contains("e2e4"))
  check(HintComposer.compose(TrainingGoal.MATE,result).contains("не подтверждён"))
  var rejected=false
  try { engine.analyze("bad fen") } catch (_: IllegalArgumentException) { rejected=true }
  check(rejected)
  println("CORE_TESTS_OK: UCI handshake, bestmove, ponder, score, depth, 3 hint modes, malformed FEN rejection")
}
