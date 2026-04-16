package com.gradesnap.omr

import com.google.gson.annotations.SerializedName

// ─── Bubble detection result (in-memory only, no Gson serialization) ──────────
data class BubbleResult(
    val filledSet: Set<Int>,
    val rawRatios: List<Double>,
    val isSuspicious: Boolean
)

// ─── Raw OMR scan output (in-memory only, no Gson serialization) ──────────────
data class RawScanResult(
    val mssv: String?,
    val examCode: String?,
    val answers: Map<Int, Set<String>>,
    val suspicious: MutableSet<Int>,
    val rawRatios: Map<Int, List<Double>>,
    val warpedImagePath: String?
)

// ─── Answer key — serialized to disk via Gson ─────────────────────────────────
// @SerializedName đảm bảo tên JSON cố định, ProGuard có thể rename field thoải mái
data class AnswerKey(
    @SerializedName("examCode")       val examCode: String,
    @SerializedName("answers")        val answers: Map<Int, Set<String>>,
    @SerializedName("warpedImagePath") val warpedImagePath: String? = null
)

// ─── Per-question result — embedded trong StudentResult ───────────────────────
data class QuestionResult(
    @SerializedName("questionNum")     val questionNum: Int,
    @SerializedName("studentAnswer")   val studentAnswer: Set<String>,
    @SerializedName("correctAnswer")   val correctAnswer: Set<String>,
    @SerializedName("isCorrect")       val isCorrect: Boolean,
    @SerializedName("isSuspicious")    var isSuspicious: Boolean,
    @SerializedName("isManuallyEdited") var isManuallyEdited: Boolean = false
)

// ─── Full student result — serialized to disk via Gson ────────────────────────
data class StudentResult(
    @SerializedName("id")               val id: String,
    @SerializedName("mssv")             val mssv: String?,
    @SerializedName("examCode")         val examCode: String?,
    @SerializedName("score")            val score: Double,
    @SerializedName("rawScore")         val rawScore: Int,
    @SerializedName("totalQuestions")   val totalQuestions: Int,
    @SerializedName("questions")        val questions: List<QuestionResult>,
    @SerializedName("warpedImagePath")  val warpedImagePath: String?,
    @SerializedName("sourceFileName")   val sourceFileName: String,
    @SerializedName("hasSuspicious")    val hasSuspicious: Boolean
)

// ─── Student info — serialized to disk via Gson ───────────────────────────────
data class StudentInfo(
    @SerializedName("mssv") val mssv: String,
    @SerializedName("name") val name: String
)

// ─── Exam project — serialized to disk via Gson ───────────────────────────────
data class ExamProject(
    @SerializedName("id")                   val id: String,
    @SerializedName("name")                 val name: String,
    @SerializedName("createdAt")            val createdAt: Long,
    @SerializedName("assignmentImageUris")  var assignmentImageUris: MutableList<String> = mutableListOf(),
    @SerializedName("answerKeyImageUris")   var answerKeyImageUris: MutableList<String> = mutableListOf(),
    @SerializedName("studentCount")         var studentCount: Int = 0,
    @SerializedName("answerKeyCount")       var answerKeyCount: Int = 0
)

// ─── Processing log (in-memory only, no Gson serialization) ──────────────────
data class ProcessingLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }
