package com.gateshot.session

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.session.data.MediaEntity
import com.gateshot.session.data.MediaType
import com.gateshot.session.data.RunEntity
import com.gateshot.session.data.SessionEntity
import com.gateshot.session.db.SessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionFeatureModule @Inject constructor(
    private val dao: SessionDao,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "session"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun initialize() {
        // Auto-save media when burst completes
        eventBus.collect<AppEvent.BurstCompleted>(scope) { event ->
            val session = dao.getActiveSession() ?: return@collect
            val run = dao.getActiveRun(session.id) ?: return@collect
            // Burst module saves files — we just record metadata
            dao.insertMedia(
                MediaEntity(
                    runId = run.id,
                    type = MediaType.BURST_FRAME,
                    fileUri = "burst://${event.sessionId}",
                    captureTimestamp = System.currentTimeMillis()
                )
            )
        }

        // Auto-tag bib when detected
        eventBus.collect<AppEvent.BibDetected>(scope) { event ->
            // Tag most recent media with detected bib
            val session = dao.getActiveSession() ?: return@collect
            val run = dao.getActiveRun(session.id) ?: return@collect
            val media = dao.getMediaForRun(run.id).lastOrNull() ?: return@collect
            dao.tagBib(media.id, event.bibNumber)
        }
    }

    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        CreateSession(),
        EndSession(),
        GetCurrentSession(),
        ListSessions(),
        StartRun(),
        EndRun(),
        ListRuns(),
        ListMedia(),
        TagMedia(),
        RecordMedia()
    )

    override fun healthCheck(): ModuleHealth {
        return ModuleHealth(name, ModuleHealth.Status.OK)
    }

    // --- session/create ---
    inner class CreateSession : ApiEndpoint<CreateSessionRequest, SessionEntity> {
        override val path = "session/create"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: CreateSessionRequest): ApiResponse<SessionEntity> {
            // End any existing active session
            dao.getActiveSession()?.let { dao.endSession(it.id) }

            val session = SessionEntity(
                eventName = request.eventName,
                discipline = request.discipline,
                date = request.date ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            )
            val id = dao.insertSession(session)
            val created = dao.getSession(id)!!

            // Auto-start run 1
            dao.insertRun(RunEntity(sessionId = id, runNumber = 1))

            return ApiResponse.success(created)
        }
    }

    // --- session/end ---
    inner class EndSession : ApiEndpoint<Unit, Boolean> {
        override val path = "session/end"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            val session = dao.getActiveSession()
                ?: return ApiResponse.error(404, "No active session")
            // End active run first
            dao.getActiveRun(session.id)?.let { dao.endRun(it.id) }
            dao.endSession(session.id)
            return ApiResponse.success(true)
        }
    }

    // --- session/current ---
    inner class GetCurrentSession : ApiEndpoint<Unit, SessionInfo?> {
        override val path = "session/current"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<SessionInfo?> {
            val session = dao.getActiveSession()
            if (session == null) return ApiResponse.success(null)
            val runs = dao.getRunsForSession(session.id)
            val mediaCount = dao.getMediaCountForSession(session.id)
            val activeRun = dao.getActiveRun(session.id)
            return ApiResponse.success(
                SessionInfo(
                    session = session,
                    runCount = runs.size,
                    mediaCount = mediaCount,
                    activeRunNumber = activeRun?.runNumber
                )
            )
        }
    }

    // --- session/list ---
    inner class ListSessions : ApiEndpoint<Unit, List<SessionEntity>> {
        override val path = "session/list"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<List<SessionEntity>> {
            // Collect current value from flow
            var sessions = emptyList<SessionEntity>()
            val job = scope.launch {
                dao.getAllSessions().collect { sessions = it }
            }
            // Give it a moment to collect
            kotlinx.coroutines.delay(50)
            job.cancel()
            return ApiResponse.success(sessions)
        }
    }

    // --- session/run/start ---
    inner class StartRun : ApiEndpoint<Unit, RunEntity> {
        override val path = "session/run/start"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<RunEntity> {
            val session = dao.getActiveSession()
                ?: return ApiResponse.error(404, "No active session")
            // End current active run
            dao.getActiveRun(session.id)?.let { dao.endRun(it.id) }
            // Start next run
            val nextRunNumber = dao.getMaxRunNumber(session.id) + 1
            val run = RunEntity(sessionId = session.id, runNumber = nextRunNumber)
            val id = dao.insertRun(run)
            return ApiResponse.success(dao.getRun(id)!!)
        }
    }

    // --- session/run/end ---
    inner class EndRun : ApiEndpoint<Unit, Boolean> {
        override val path = "session/run/end"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            val session = dao.getActiveSession()
                ?: return ApiResponse.error(404, "No active session")
            val run = dao.getActiveRun(session.id)
                ?: return ApiResponse.error(404, "No active run")
            dao.endRun(run.id)
            return ApiResponse.success(true)
        }
    }

    // --- session/run/list ---
    inner class ListRuns : ApiEndpoint<Long, List<RunEntity>> {
        override val path = "session/run/list"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Long): ApiResponse<List<RunEntity>> {
            return ApiResponse.success(dao.getRunsForSession(request))
        }
    }

    // --- session/media/list ---
    inner class ListMedia : ApiEndpoint<MediaQuery, List<MediaEntity>> {
        override val path = "session/media/list"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: MediaQuery): ApiResponse<List<MediaEntity>> {
            val media = when {
                request.runId != null -> dao.getMediaForRun(request.runId)
                request.sessionId != null -> dao.getMediaForSession(request.sessionId)
                request.bibNumber != null -> dao.getMediaByBib(request.bibNumber)
                request.minRating != null -> dao.getMediaByRating(request.minRating)
                request.flaggedOnly -> dao.getFlaggedMedia()
                else -> emptyList()
            }
            return ApiResponse.success(media)
        }
    }

    // --- session/media/tag ---
    inner class TagMedia : ApiEndpoint<TagRequest, Boolean> {
        override val path = "session/media/tag"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: TagRequest): ApiResponse<Boolean> {
            request.bibNumber?.let { dao.tagBib(request.mediaId, it) }
            request.starRating?.let { dao.setRating(request.mediaId, it) }
            request.flagged?.let { dao.setFlagged(request.mediaId, it) }
            return ApiResponse.success(true)
        }
    }

    // --- session/media/record (internal — other modules use this to save media) ---
    inner class RecordMedia : ApiEndpoint<MediaEntity, Long> {
        override val path = "session/media/record"
        override val module = "session"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: MediaEntity): ApiResponse<Long> {
            val id = dao.insertMedia(request)
            return ApiResponse.success(id)
        }
    }
}

data class CreateSessionRequest(
    val eventName: String,
    val discipline: String,
    val date: String? = null
)

data class SessionInfo(
    val session: SessionEntity,
    val runCount: Int,
    val mediaCount: Int,
    val activeRunNumber: Int?
)

data class MediaQuery(
    val sessionId: Long? = null,
    val runId: Long? = null,
    val bibNumber: Int? = null,
    val minRating: Int? = null,
    val flaggedOnly: Boolean = false
)

data class TagRequest(
    val mediaId: Long,
    val bibNumber: Int? = null,
    val starRating: Int? = null,
    val flagged: Boolean? = null
)
