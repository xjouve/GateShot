package com.gateshot.coaching.timing

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimingFeatureModule @Inject constructor(
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "timing"
    override val version = "0.1.0"
    override val requiredMode = AppMode.COACH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Splits per run: runId -> list of splits
    private val splitsByRun = mutableMapOf<String, MutableList<Split>>()
    private var activeRunId: String? = null
    private var nextGateNumber = 1

    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        StartTimingRun(),
        RecordSplit(),
        ListSplits(),
        DeleteSplit(),
        CompareSplits(),
        SyncWithVideo()
    )

    override fun healthCheck(): ModuleHealth {
        val activeSplits = activeRunId?.let { splitsByRun[it]?.size } ?: 0
        return ModuleHealth(name, ModuleHealth.Status.OK, "Active: $activeSplits splits")
    }

    // --- coach/timing/run/start ---
    inner class StartTimingRun : ApiEndpoint<String, Boolean> {
        override val path = "coach/timing/run/start"
        override val module = "timing"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            activeRunId = request
            splitsByRun.getOrPut(request) { mutableListOf() }
            nextGateNumber = 1
            return ApiResponse.success(true)
        }
    }

    // --- coach/timing/split/record ---
    inner class RecordSplit : ApiEndpoint<Unit, Split> {
        override val path = "coach/timing/split/record"
        override val module = "timing"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<Split> {
            val runId = activeRunId
                ?: return ApiResponse.error(404, "No active timing run")
            val splits = splitsByRun.getOrPut(runId) { mutableListOf() }
            val split = Split(
                gateNumber = nextGateNumber++,
                timestamp = System.currentTimeMillis(),
                elapsedMs = if (splits.isEmpty()) 0L
                    else System.currentTimeMillis() - splits.first().timestamp,
                splitMs = if (splits.isEmpty()) 0L
                    else System.currentTimeMillis() - splits.last().timestamp
            )
            splits.add(split)
            eventBus.publish(AppEvent.SplitRecorded(split.gateNumber, split.timestamp))
            return ApiResponse.success(split)
        }
    }

    // --- coach/timing/split/list ---
    inner class ListSplits : ApiEndpoint<String, List<Split>> {
        override val path = "coach/timing/split/list"
        override val module = "timing"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<List<Split>> {
            return ApiResponse.success(splitsByRun[request] ?: emptyList())
        }
    }

    // --- coach/timing/split/delete ---
    inner class DeleteSplit : ApiEndpoint<DeleteSplitRequest, Boolean> {
        override val path = "coach/timing/split/delete"
        override val module = "timing"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: DeleteSplitRequest): ApiResponse<Boolean> {
            splitsByRun[request.runId]?.removeAll { it.gateNumber == request.gateNumber }
            return ApiResponse.success(true)
        }
    }

    // --- coach/timing/compare ---
    inner class CompareSplits : ApiEndpoint<CompareRequest, List<SplitDelta>> {
        override val path = "coach/timing/compare"
        override val module = "timing"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: CompareRequest): ApiResponse<List<SplitDelta>> {
            val splitsA = splitsByRun[request.runIdA] ?: return ApiResponse.error(404, "Run A not found")
            val splitsB = splitsByRun[request.runIdB] ?: return ApiResponse.error(404, "Run B not found")

            val maxGates = minOf(splitsA.size, splitsB.size)
            val deltas = (0 until maxGates).map { i ->
                SplitDelta(
                    gateNumber = i + 1,
                    timeA = splitsA[i].elapsedMs,
                    timeB = splitsB[i].elapsedMs,
                    deltaMs = splitsB[i].elapsedMs - splitsA[i].elapsedMs
                )
            }
            return ApiResponse.success(deltas)
        }
    }

    // --- coach/timing/sync/video ---
    inner class SyncWithVideo : ApiEndpoint<TimingSyncRequest, List<TimedFrame>> {
        override val path = "coach/timing/sync/video"
        override val module = "timing"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: TimingSyncRequest): ApiResponse<List<TimedFrame>> {
            val splits = splitsByRun[request.runId]
                ?: return ApiResponse.error(404, "Run not found")
            // Map each split timestamp to a video frame position
            val videoStartMs = request.videoStartTimestamp
            val frames = splits.map { split ->
                TimedFrame(
                    gateNumber = split.gateNumber,
                    videoPositionMs = split.timestamp - videoStartMs,
                    splitTimeMs = split.elapsedMs
                )
            }
            return ApiResponse.success(frames)
        }
    }
}

@Serializable
data class Split(
    val gateNumber: Int,
    val timestamp: Long,
    val elapsedMs: Long,    // Time since first split (start)
    val splitMs: Long       // Time since previous split
)

data class DeleteSplitRequest(val runId: String, val gateNumber: Int)
data class CompareRequest(val runIdA: String, val runIdB: String)
data class SplitDelta(val gateNumber: Int, val timeA: Long, val timeB: Long, val deltaMs: Long)
data class TimingSyncRequest(val runId: String, val videoStartTimestamp: Long)
data class TimedFrame(val gateNumber: Int, val videoPositionMs: Long, val splitTimeMs: Long)
