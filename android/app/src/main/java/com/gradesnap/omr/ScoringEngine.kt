package com.gradesnap.omr

/**
 * Chấm điểm bài làm dựa trên đáp án.
 *
 * Quy tắc:
 * - So sánh set đáp án học viên với set đáp án chuẩn (hỗ trợ multi-answer).
 * - Nếu câu suspicious trong bài làm nhưng đáp án của câu đó khớp đúng với key
 *   (kể cả key cũng multi-fill giống nhau) → tự động bỏ suspicious cho câu đó.
 * - Điểm = (số câu đúng / tổng câu) × 10, làm tròn 2 chữ số thập phân.
 */
object ScoringEngine {

    /**
     * Chấm điểm một bài làm dựa theo đáp án.
     *
     * @param scan      kết quả scan thô của học viên
     * @param answerKey đáp án chuẩn (theo mã đề)
     * @return StudentResult đầy đủ, suspicious đã được resolved
     */
    fun score(scan: RawScanResult, answerKey: AnswerKey, sourceFileName: String): StudentResult {
        val questions = mutableListOf<QuestionResult>()
        var correct = 0

        // Duyệt toàn bộ câu trong đáp án
        for ((qNum, correctAnswer) in answerKey.answers.entries.sortedBy { it.key }) {
            val studentAnswer = scan.answers[qNum] ?: emptySet()
            val isCorrect = studentAnswer == correctAnswer

            // Bỏ suspicious nếu bài làm khớp đúng đáp án
            var isSuspicious = scan.suspicious.contains(qNum)
            if (isSuspicious && isCorrect) {
                isSuspicious = false
                scan.suspicious.remove(qNum)
            }

            if (isCorrect) correct++

            questions.add(
                QuestionResult(
                    questionNum    = qNum,
                    studentAnswer  = studentAnswer,
                    correctAnswer  = correctAnswer,
                    isCorrect      = isCorrect,
                    isSuspicious   = isSuspicious
                )
            )
        }

        val totalQ = answerKey.answers.size
        val score10 = if (totalQ > 0) (correct.toDouble() / totalQ * 10.0) else 0.0

        val id = scan.mssv ?: sourceFileName.substringBeforeLast(".")

        return StudentResult(
            id              = id,
            mssv            = scan.mssv,
            examCode        = scan.examCode,
            score           = Math.round(score10 * 100.0) / 100.0,
            rawScore        = correct,
            totalQuestions  = totalQ,
            questions       = questions,
            warpedImagePath = scan.warpedImagePath,
            sourceFileName  = sourceFileName,
            hasSuspicious   = questions.any { it.isSuspicious }
        )
    }

    /**
     * Tính lại điểm sau khi giáo viên chỉnh sửa tay.
     */
    fun recalculate(result: StudentResult, editedQuestions: List<QuestionResult>): StudentResult {
        var correct = 0
        val newQuestions = editedQuestions.map { q ->
            val isCorrect = q.studentAnswer == q.correctAnswer
            if (isCorrect) correct++
            q.copy(isCorrect = isCorrect)
        }
        val totalQ = newQuestions.size
        val score10 = if (totalQ > 0) (correct.toDouble() / totalQ * 10.0) else 0.0

        return result.copy(
            score          = Math.round(score10 * 100.0) / 100.0,
            rawScore       = correct,
            totalQuestions = totalQ,
            questions      = newQuestions,
            hasSuspicious  = newQuestions.any { it.isSuspicious && !it.isManuallyEdited }
        )
    }

    // ─── Dashboard helpers ─────────────────────────────────────────────────────

    fun average(results: List<StudentResult>): Double {
        if (results.isEmpty()) return 0.0
        return results.sumOf { it.score } / results.size
    }

    fun countPerfect(results: List<StudentResult>) =
        results.count { it.score >= 10.0 }

    fun countZero(results: List<StudentResult>) =
        results.count { it.score == 0.0 }

    fun countWithSuspicious(results: List<StudentResult>) =
        results.count { it.hasSuspicious }

    fun sortedByScore(results: List<StudentResult>) =
        results.sortedByDescending { it.score }
}
