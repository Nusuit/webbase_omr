package com.gradesnap.omr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * File-based persistence cho ExamProject.
 * Mỗi project lưu trong: [ExternalFilesDir]/exams/{id}/project.json
 * Đáp án:              [ExternalFilesDir]/exams/{id}/keys/{examCode}.json
 */
class ExamRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val baseDir: File
        get() = File(context.getExternalFilesDir(null), "exams").also { it.mkdirs() }

    // ─── Projects ─────────────────────────────────────────────────────────────

    fun createProject(name: String): ExamProject {
        val project = ExamProject(
            id        = UUID.randomUUID().toString(),
            name      = name,
            createdAt = System.currentTimeMillis()
        )
        saveProject(project)
        return project
    }

    fun saveProject(project: ExamProject) {
        val dir = projectDir(project.id).also { it.mkdirs() }
        // Atomic write: ghi ra file tạm rồi rename để tránh corrupt khi crash giữa chừng
        val target = File(dir, "project.json")
        val tmp    = File(dir, "project.json.tmp")
        tmp.writeText(gson.toJson(project))
        tmp.renameTo(target)
    }

    fun loadProject(id: String): ExamProject? {
        val dir  = projectDir(id)
        val file = File(dir, "project.json")
        val tmp  = File(dir, "project.json.tmp")
        // Nếu file chính bị corrupt, thử phục hồi từ bản tmp (atomic-write còn sót)
        val target = when {
            file.exists() -> file
            tmp.exists()  -> tmp
            else          -> return null
        }
        val p = runCatching { gson.fromJson(target.readText(), ExamProject::class.java) }
            .getOrNull() ?: return null
        // Gson có thể để null cho MutableList nếu tên field bị obfuscate (ProGuard cache cũ).
        // Sanitize để tránh NPE khi truy cập .size / .isEmpty()
        @Suppress("SENSELESS_COMPARISON")
        if (p.assignmentImageUris == null) p.assignmentImageUris = mutableListOf()
        @Suppress("SENSELESS_COMPARISON")
        if (p.answerKeyImageUris == null) p.answerKeyImageUris = mutableListOf()
        return p
    }

    fun listProjects(): List<ExamProject> {
        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val f = File(dir, "project.json")
                if (f.exists()) runCatching { gson.fromJson(f.readText(), ExamProject::class.java) }.getOrNull()
                else null
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun deleteProject(id: String) {
        projectDir(id).deleteRecursively()
    }
    // ─── Student List ──────────────────────────────────────────────────────────────

    fun saveStudentList(projectId: String, students: List<StudentInfo>) {
        val dir = projectDir(projectId).also { it.mkdirs() }
        File(dir, "students.json").writeText(gson.toJson(students))
    }

    fun loadStudentList(projectId: String): List<StudentInfo> {
        val file = File(projectDir(projectId), "students.json")
        if (!file.exists()) return emptyList()
        val type = TypeToken.getParameterized(List::class.java, StudentInfo::class.java).type
        return runCatching { gson.fromJson<List<StudentInfo>>(file.readText(), type) }.getOrNull() ?: emptyList()
    }

    fun loadStudentMap(projectId: String): Map<String, String> =
        loadStudentList(projectId).associate { it.mssv to it.name }
    // ─── Answer Keys ───────────────────────────────────────────────────────────

    fun saveAnswerKey(projectId: String, key: AnswerKey) {
        val dir = File(projectDir(projectId), "keys").also { it.mkdirs() }
        File(dir, "${key.examCode}.json").writeText(gson.toJson(key))
        // Update project count
        loadProject(projectId)?.let { proj ->
            proj.answerKeyCount = dir.listFiles { f -> f.extension == "json" }?.size ?: proj.answerKeyCount
            saveProject(proj)
        }
    }

    fun loadAnswerKey(projectId: String, examCode: String): AnswerKey? {
        val file = File(File(projectDir(projectId), "keys"), "$examCode.json")
        if (!file.exists()) return null
        return gson.fromJson(file.readText(), AnswerKey::class.java)
    }

    fun loadAllAnswerKeys(projectId: String): Map<String, AnswerKey> {
        val dir = File(projectDir(projectId), "keys")
        if (!dir.exists()) return emptyMap()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { gson.fromJson(f.readText(), AnswerKey::class.java) }.getOrNull() }
            ?.associateBy { it.examCode }
            ?: emptyMap()
    }

    // ─── Student Results ───────────────────────────────────────────────────────

    fun saveStudentResult(projectId: String, result: StudentResult) {
        val dir = File(projectDir(projectId), "results").also { it.mkdirs() }
        val safeName = result.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        // Xóa bất kỳ file cũ nào có cùng sourceFileName nhưng id khác (tránh duplicate khi nhập MSSV/mã đề thủ công)
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            val existing = runCatching { gson.fromJson(f.readText(), StudentResult::class.java) }.getOrNull()
            if (existing != null && existing.sourceFileName == result.sourceFileName) {
                val existingSafe = existing.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                if (existingSafe != safeName) f.delete()
            }
        }
        File(dir, "$safeName.json").writeText(gson.toJson(result))
        // Update project count
        loadProject(projectId)?.let { proj ->
            proj.studentCount = dir.listFiles { f -> f.extension == "json" }?.size ?: proj.studentCount
            saveProject(proj)
        }
    }

    fun deleteStudentResult(projectId: String, resultId: String) {
        val dir = File(projectDir(projectId), "results")
        val safeName = resultId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        File(dir, "$safeName.json").delete()
        loadProject(projectId)?.let { proj ->
            proj.studentCount = dir.listFiles { f -> f.extension == "json" }?.size ?: proj.studentCount
            saveProject(proj)
        }
    }

    /** Xóa toàn bộ kết quả bài làm của 1 project. */
    fun clearAllResults(projectId: String) {
        val dir = File(projectDir(projectId), "results")
        dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
        loadProject(projectId)?.let { proj -> proj.studentCount = 0; saveProject(proj) }
    }

    /** Xóa toàn bộ đáp án (answer keys) của 1 project. */
    fun clearAllAnswerKeys(projectId: String) {
        val dir = File(projectDir(projectId), "keys")
        dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
        loadProject(projectId)?.let { proj -> proj.answerKeyCount = 0; saveProject(proj) }
    }

    fun loadStudentResult(projectId: String, studentId: String): StudentResult? {
        return loadAllStudentResults(projectId).find { it.id == studentId }
    }

    fun loadAllStudentResults(projectId: String): List<StudentResult> {
        val dir = File(projectDir(projectId), "results")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    val r = gson.fromJson(f.readText(), StudentResult::class.java) ?: return@runCatching null
                    // Sanitize: questions có thể null nếu ProGuard cache cũ obfuscate field name
                    @Suppress("SENSELESS_COMPARISON")
                    if (r.questions == null) r.copy(questions = emptyList()) else r
                }.getOrNull()
            }
            ?.sortedByDescending { it.score }
            ?: emptyList()
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun projectDir(id: String) = File(baseDir, id)
}
