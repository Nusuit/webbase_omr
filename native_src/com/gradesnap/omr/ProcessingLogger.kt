package com.gradesnap.omr

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Observable logger dùng trong batch processing.
 * Emit trên coroutine scope, thu thập từ UI để hiển thị log real-time.
 */
class ProcessingLogger {
    private val _logs = MutableSharedFlow<ProcessingLog>(replay = 200, extraBufferCapacity = 500)
    val logs = _logs.asSharedFlow()

    private val _logList = mutableListOf<ProcessingLog>()
    val allLogs: List<ProcessingLog> get() = _logList.toList()

    suspend fun info(msg: String) = emit(LogLevel.INFO, msg)
    suspend fun warn(msg: String) = emit(LogLevel.WARN, msg)
    suspend fun error(msg: String) = emit(LogLevel.ERROR, msg)
    suspend fun success(msg: String) = emit(LogLevel.SUCCESS, msg)

    private suspend fun emit(level: LogLevel, msg: String) {
        val log = ProcessingLog(level = level, message = msg)
        _logList.add(log)
        _logs.emit(log)
    }

    /** Tạo callback string cho OmrProcessor log — emit vào Flow ngay (tryEmit). */
    fun blockingCallback(level: LogLevel = LogLevel.INFO): (String) -> Unit = { msg ->
        val log = ProcessingLog(level = level, message = msg)
        _logList.add(log)
        _logs.tryEmit(log)
    }

    fun clear() = _logList.clear()
}
