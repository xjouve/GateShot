package com.gateshot.coaching.athlete

import com.gateshot.coaching.athlete.data.AthleteDrillEntity
import com.gateshot.coaching.athlete.data.AthleteEntity
import com.gateshot.coaching.athlete.data.AthleteErrorEntity
import com.gateshot.coaching.athlete.data.AthleteProgressEntity
import com.gateshot.coaching.athlete.db.AthleteDao
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AthleteFeatureModule @Inject constructor(
    private val dao: AthleteDao,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "athlete"
    override val version = "0.1.0"
    override val requiredMode = AppMode.COACH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun initialize() {
        // Auto-link media to athlete when bib is detected
        eventBus.collect<AppEvent.BibDetected>(scope) { event ->
            val athlete = dao.getAthleteByBib(event.bibNumber.toString())
            if (athlete != null) {
                // Athlete found — media linking handled by session module
            }
        }
    }

    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        CreateAthlete(), UpdateAthlete(), GetAthlete(), ListAthletes(),
        DeleteAthlete(), SearchAthletes(),
        AddError(), GetErrors(),
        AssignDrill(), GetDrills(), CompleteDrill(),
        AddProgress(), GetProgress(), GetTimeline()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    // --- coach/athlete/create ---
    inner class CreateAthlete : ApiEndpoint<AthleteEntity, Long> {
        override val path = "coach/athlete/create"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: AthleteEntity): ApiResponse<Long> {
            val id = dao.insertAthlete(request)
            return ApiResponse.success(id)
        }
    }

    // --- coach/athlete/update ---
    inner class UpdateAthlete : ApiEndpoint<AthleteEntity, Boolean> {
        override val path = "coach/athlete/update"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: AthleteEntity): ApiResponse<Boolean> {
            dao.updateAthlete(request.copy(updatedAt = System.currentTimeMillis()))
            return ApiResponse.success(true)
        }
    }

    // --- coach/athlete/get ---
    inner class GetAthlete : ApiEndpoint<Long, AthleteEntity?> {
        override val path = "coach/athlete/get"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Long): ApiResponse<AthleteEntity?> {
            return ApiResponse.success(dao.getAthlete(request))
        }
    }

    // --- coach/athlete/list ---
    inner class ListAthletes : ApiEndpoint<Unit, List<AthleteEntity>> {
        override val path = "coach/athlete/list"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Unit): ApiResponse<List<AthleteEntity>> {
            return ApiResponse.success(dao.searchAthletes(""))
        }
    }

    // --- coach/athlete/delete ---
    inner class DeleteAthlete : ApiEndpoint<Long, Boolean> {
        override val path = "coach/athlete/delete"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Long): ApiResponse<Boolean> {
            dao.deleteAthlete(request)
            return ApiResponse.success(true)
        }
    }

    // --- coach/athlete/search ---
    inner class SearchAthletes : ApiEndpoint<String, List<AthleteEntity>> {
        override val path = "coach/athlete/search"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: String): ApiResponse<List<AthleteEntity>> {
            return ApiResponse.success(dao.searchAthletes(request))
        }
    }

    // --- coach/athlete/error/add ---
    inner class AddError : ApiEndpoint<AthleteErrorEntity, Long> {
        override val path = "coach/athlete/error/add"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: AthleteErrorEntity): ApiResponse<Long> {
            return ApiResponse.success(dao.insertError(request))
        }
    }

    // --- coach/athlete/errors ---
    inner class GetErrors : ApiEndpoint<Long, List<AthleteErrorEntity>> {
        override val path = "coach/athlete/errors"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Long): ApiResponse<List<AthleteErrorEntity>> {
            return ApiResponse.success(dao.getErrorsForAthlete(request))
        }
    }

    // --- coach/athlete/drill/assign ---
    inner class AssignDrill : ApiEndpoint<AthleteDrillEntity, Long> {
        override val path = "coach/athlete/drill/assign"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: AthleteDrillEntity): ApiResponse<Long> {
            return ApiResponse.success(dao.insertDrill(request))
        }
    }

    // --- coach/athlete/drills ---
    inner class GetDrills : ApiEndpoint<Long, List<AthleteDrillEntity>> {
        override val path = "coach/athlete/drills"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Long): ApiResponse<List<AthleteDrillEntity>> {
            return ApiResponse.success(dao.getDrillsForAthlete(request))
        }
    }

    // --- coach/athlete/drill/complete ---
    inner class CompleteDrill : ApiEndpoint<Long, Boolean> {
        override val path = "coach/athlete/drill/complete"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Long): ApiResponse<Boolean> {
            dao.completeDrill(request)
            return ApiResponse.success(true)
        }
    }

    // --- coach/athlete/progress/add ---
    inner class AddProgress : ApiEndpoint<AthleteProgressEntity, Long> {
        override val path = "coach/athlete/progress/add"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: AthleteProgressEntity): ApiResponse<Long> {
            return ApiResponse.success(dao.insertProgress(request))
        }
    }

    // --- coach/athlete/progress ---
    inner class GetProgress : ApiEndpoint<Long, List<AthleteProgressEntity>> {
        override val path = "coach/athlete/progress"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: Long): ApiResponse<List<AthleteProgressEntity>> {
            return ApiResponse.success(dao.getProgressForAthlete(request))
        }
    }

    // --- coach/athlete/progress/timeline ---
    inner class GetTimeline : ApiEndpoint<TimelineRequest, List<AthleteProgressEntity>> {
        override val path = "coach/athlete/progress/timeline"
        override val module = "athlete"
        override val requiredMode = AppMode.COACH
        override suspend fun handle(request: TimelineRequest): ApiResponse<List<AthleteProgressEntity>> {
            return ApiResponse.success(dao.getProgressTimeline(request.athleteId, request.metric))
        }
    }
}

data class TimelineRequest(val athleteId: Long, val metric: String)
