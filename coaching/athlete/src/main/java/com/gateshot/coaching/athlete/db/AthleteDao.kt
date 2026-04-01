package com.gateshot.coaching.athlete.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gateshot.coaching.athlete.data.AthleteDrillEntity
import com.gateshot.coaching.athlete.data.AthleteEntity
import com.gateshot.coaching.athlete.data.AthleteErrorEntity
import com.gateshot.coaching.athlete.data.AthleteProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AthleteDao {

    // --- Athletes ---
    @Insert
    suspend fun insertAthlete(athlete: AthleteEntity): Long

    @Update
    suspend fun updateAthlete(athlete: AthleteEntity)

    @Query("SELECT * FROM athletes ORDER BY name")
    fun getAllAthletes(): Flow<List<AthleteEntity>>

    @Query("SELECT * FROM athletes WHERE id = :id")
    suspend fun getAthlete(id: Long): AthleteEntity?

    @Query("SELECT * FROM athletes WHERE bibNumbers LIKE '%' || :bib || '%'")
    suspend fun getAthleteByBib(bib: String): AthleteEntity?

    @Query("SELECT * FROM athletes WHERE name LIKE '%' || :query || '%'")
    suspend fun searchAthletes(query: String): List<AthleteEntity>

    @Query("DELETE FROM athletes WHERE id = :id")
    suspend fun deleteAthlete(id: Long)

    // --- Errors ---
    @Insert
    suspend fun insertError(error: AthleteErrorEntity): Long

    @Update
    suspend fun updateError(error: AthleteErrorEntity)

    @Query("SELECT * FROM athlete_errors WHERE athleteId = :athleteId ORDER BY lastSeen DESC")
    suspend fun getErrorsForAthlete(athleteId: Long): List<AthleteErrorEntity>

    @Query("SELECT * FROM athlete_errors WHERE athleteId = :athleteId AND trend = 'regressing' ORDER BY lastSeen DESC")
    suspend fun getRegressingErrors(athleteId: Long): List<AthleteErrorEntity>

    // --- Drills ---
    @Insert
    suspend fun insertDrill(drill: AthleteDrillEntity): Long

    @Query("SELECT * FROM athlete_drills WHERE athleteId = :athleteId ORDER BY assignedAt DESC")
    suspend fun getDrillsForAthlete(athleteId: Long): List<AthleteDrillEntity>

    @Query("SELECT * FROM athlete_drills WHERE athleteId = :athleteId AND completedAt IS NULL")
    suspend fun getActiveDrills(athleteId: Long): List<AthleteDrillEntity>

    @Query("UPDATE athlete_drills SET completedAt = :completedAt WHERE id = :drillId")
    suspend fun completeDrill(drillId: Long, completedAt: Long = System.currentTimeMillis())

    // --- Progress ---
    @Insert
    suspend fun insertProgress(progress: AthleteProgressEntity): Long

    @Query("SELECT * FROM athlete_progress WHERE athleteId = :athleteId ORDER BY date DESC")
    suspend fun getProgressForAthlete(athleteId: Long): List<AthleteProgressEntity>

    @Query("SELECT * FROM athlete_progress WHERE athleteId = :athleteId AND metric = :metric ORDER BY date")
    suspend fun getProgressTimeline(athleteId: Long, metric: String): List<AthleteProgressEntity>
}
