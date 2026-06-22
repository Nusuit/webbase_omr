package com.gradesnap.omr

data class SheetCapture(
    val sheet: Int,
    val provider: String,
    val cppMs: Long,
    val e2eMs: Long,
    val ortMs: Long,
    val jpegDecodeMs: Long,
    val jpegWriteMs: Long,
    val failed: Boolean,
    val examCode: String?,
    val mssv: String?,
    val mssvValid: Boolean,
    val keyValid: Boolean,
    val answered: Int,
    val suspicious: Int,
    val multi: Int,
    val sourceFile: String?,
    // Per-question predictions: qNum (1-based) -> set of selected options ({"A"}, {"A","B"}, or empty).
    // Serialized as auto_q01..auto_q60 strings ("A", "A+B", "") to match the Web review/eval schema.
    val answers: Map<Int, Set<String>> = emptyMap(),
    // Same-boundary stage timers (ms) for cross-platform decomposition; 0 when not measured.
    val bitmapToMatMs: Long = 0L,
    val normalizeMs:   Long = 0L,
    val threshMs:      Long = 0L,
    val omrDetectMs:   Long = 0L,
    val yoloPrepMs:    Long = 0L,
    val yoloPostMs:    Long = 0L
)
