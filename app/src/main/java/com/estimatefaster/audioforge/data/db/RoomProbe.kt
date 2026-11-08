package com.estimatefaster.audioforge.data.db

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** P0-05 Room 探针状态；首页调试卡直接订阅。 */
sealed interface RoomProbeState {
    data object Idle : RoomProbeState
    data object Running : RoomProbeState
    data class Passed(val counter: Int, val elapsedMs: Long) : RoomProbeState
    data class Failed(val message: String) : RoomProbeState
}

/**
 * 启动时写一行、再回读校验，覆盖 Room 建库、KSP 生成的 DAO 与协程桥接。
 * 结果同时写 logcat（TAG=[TAG]）与 [state]，便于无设备时从 CI/日志排查。
 */
class RoomProbe(
    private val dao: ProbeDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<RoomProbeState>(RoomProbeState.Idle)
    val state: StateFlow<RoomProbeState> = _state.asStateFlow()

    suspend fun run(): RoomProbeState {
        _state.value = RoomProbeState.Running
        val startedAt = now()
        val result = try {
            withContext(io) {
                val previous = dao.find(ProbeEntity.PROBE_ID)
                val next = ProbeEntity(
                    id = ProbeEntity.PROBE_ID,
                    note = "room-smoke",
                    counter = (previous?.counter ?: 0) + 1,
                    updatedAt = now(),
                )
                dao.upsert(next)

                val readBack = dao.find(ProbeEntity.PROBE_ID)
                    ?: error("回读 probe 行为空")
                check(readBack.counter == next.counter) {
                    "回读计数不一致：写入 ${next.counter}，读到 ${readBack.counter}"
                }

                val elapsedMs = now() - startedAt
                RoomProbeState.Passed(counter = readBack.counter, elapsedMs = elapsedMs)
                    .also { Log.i(TAG, "room smoke passed：counter=${it.counter}，耗时 ${it.elapsedMs}ms") }
            }
        } catch (e: Exception) {
            // 不吞异常栈：logcat 需要完整的失败原因。
            val message = e.message ?: e::class.java.simpleName
            Log.e(TAG, "room smoke failed：$message", e)
            RoomProbeState.Failed(message)
        }
        _state.value = result
        return result
    }

    companion object {
        const val TAG = "AudioForgeRoomProbe"
    }
}
