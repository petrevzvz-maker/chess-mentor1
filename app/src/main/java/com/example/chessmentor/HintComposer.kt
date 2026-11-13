package com.example.chessmentor

enum class TrainingGoal(val title: String) {
    UNIVERSAL("Универсальный"), BRILLIANT("Бриллиантовый ход"), MATE("Шах и мат")
}

object HintComposer {
    fun compose(goal: TrainingGoal, result: ChessEngine.Result): String {
        val move = result.bestMove
        if (move == "(none)") return "Нет доступных ходов: возможно, мат или пат."
        return when (goal) {
            TrainingGoal.UNIVERSAL -> "Лучший ход: $move\n${result.score}. Глубина: ${result.depth}."
            TrainingGoal.BRILLIANT -> "Тактическая задача: ищи сильный ход $move.\n${result.score}. Вариант: ${result.pv.take(90)}\nВажно: Stockfish оценивает силу хода, но не присваивает официальную метку Chess.com «Brilliant»."
            TrainingGoal.MATE -> if (result.score.startsWith("мат")) {
                "Форсированный мат: начни с $move.\n${result.score}. Вариант: ${result.pv.take(110)}"
            } else {
                "В найденной глубине форсированный мат не подтверждён. Лучший ход: $move. ${result.score}."
            }
        }
    }
}
