package com.gradesnap.omr

import android.net.Uri

// ─── Bubble detection result ───────────────────────────────────────────────────
data class BubbleResult(
    val filledSet: Set<Int>,           // indices of filled bubbles (0=A,1=B,...)
    val rawRatios: List<Double>,       // white-pixel ratio for each option
    val isSuspicious: Boolean          // ambiguous fill (only applies to student scans)
)

// ─── Raw OMR scan output (before scoring) ─────────────────────────────────────
data class RawScanResult(
    val mssv: String?,
    val examCode: String?,
    val answers: Map<Int, Set<String>>,         // qNum → set of option strings
    val suspicious: MutableSet<Int>,             // question numbers with ambiguous marks
    val rawRatios: Map<Int, List<Double>>,       // qNum → ratios, for debug/log
    val warpedImagePath: String?                 // path to saved warped JPEG
)

// ─── Answer key ───────────────────────────────────────────────────────────────
data class AnswerKey(
    val examCode: String,
    val answers: Map<Int, Set<String>>,   // supports multi-answer (e.g. A+B)
    val warpedImagePath: String? = null,
    val confirmed: Boolean = false        // người dùng đã xác nhận làm ground truth
)

// ─── Per-question result ───────────────────────────────────────────────────────
data class QuestionResult(
    val questionNum: Int,
    val studentAnswer: Set<String>,
    val correctAnswer: Set<String>,
    val isCorrect: Boolean,
    var isSuspicious: Boolean,
    var isManuallyEdited: Boolean = false
)

// ─── Full student result ───────────────────────────────────────────────────────
data class StudentResult(
    val id: String,                         // unique id: mssv or filename
    val mssv: String?,
    val examCode: String?,
    val score: Double,                      // 0–10 scaled
    val rawScore: Int,                      // number of correct answers
    val totalQuestions: Int,
    val questions: List<QuestionResult>,
    val warpedImagePath: String?,
    val sourceFileName: String,
    val hasSuspicious: Boolean
)

// ─── Student info (imported from CSV) ─────────────────────────────────────────
data class StudentInfo(
    val mssv: String,
    val name: String
)

// ─── Exam project ─────────────────────────────────────────────────────────────
data class ExamProject(
    val id: String,
    val name: String,
    val createdAt: Long,
    var assignmentImageUris: MutableList<String> = mutableListOf(),
    var answerKeyImageUris: MutableList<String> = mutableListOf(),
    var studentCount: Int = 0,
    var answerKeyCount: Int = 0
)

// ─── Processing log ───────────────────────────────────────────────────────────
data class ProcessingLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }
