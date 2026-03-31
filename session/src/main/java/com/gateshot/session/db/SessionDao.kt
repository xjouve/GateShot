package com.gateshot.session.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gateshot.session.data.MediaEntity
import com.gateshot.session.data.MediaType
import com.gateshot.session.data.RunEntity
import com.gateshot.session.data.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    // --- Sessions ---
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY startTime DESC")
    suspend fun getSessionsByDate(date: String): List<SessionEntity>

    @Query("UPDATE sessions SET isActive = 0, endTime = :endTime WHERE id = :id")
    suspend fun endSession(id: Long, endTime: Long = System.currentTimeMillis())

    // --- Runs ---
    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Update
    suspend fun updateRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE sessionId = :sessionId ORDER BY runNumber")
    suspend fun getRunsForSession(sessionId: Long): List<RunEntity>

    @Query("SELECT * FROM runs WHERE isActive = 1 AND sessionId = :sessionId LIMIT 1")
    suspend fun getActiveRun(sessionId: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun getRun(id: Long): RunEntity?

    @Query("SELECT COALESCE(MAX(runNumber), 0) FROM runs WHERE sessionId = :sessionId")
    suspend fun getMaxRunNumber(sessionId: Long): Int

    @Query("UPDATE runs SET isActive = 0, endTime = :endTime WHERE id = :id")
    suspend fun endRun(id: Long, endTime: Long = System.currentTimeMillis())

    // --- Media ---
    @Insert
    suspend fun insertMedia(media: MediaEntity): Long

    @Update
    suspend fun updateMedia(media: MediaEntity)

    @Query("SELECT * FROM media WHERE runId = :runId ORDER BY captureTimestamp")
    suspend fun getMediaForRun(runId: Long): List<MediaEntity>

    @Query("SELECT * FROM media WHERE runId IN (SELECT id FROM runs WHERE sessionId = :sessionId) ORDER BY captureTimestamp")
    suspend fun getMediaForSession(sessionId: Long): List<MediaEntity>

    @Query("SELECT * FROM media WHERE bibNumber = :bib ORDER BY captureTimestamp DESC")
    suspend fun getMediaByBib(bib: Int): List<MediaEntity>

    @Query("SELECT * FROM media WHERE starRating >= :minRating ORDER BY captureTimestamp DESC")
    suspend fun getMediaByRating(minRating: Int): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isFlagged = 1 ORDER BY captureTimestamp DESC")
    suspend fun getFlaggedMedia(): List<MediaEntity>

    @Query("UPDATE media SET bibNumber = :bib WHERE id = :mediaId")
    suspend fun tagBib(mediaId: Long, bib: Int)

    @Query("UPDATE media SET starRating = :rating WHERE id = :mediaId")
    suspend fun setRating(mediaId: Long, rating: Int)

    @Query("UPDATE media SET isFlagged = :flagged WHERE id = :mediaId")
    suspend fun setFlagged(mediaId: Long, flagged: Boolean)

    @Query("SELECT COUNT(*) FROM media WHERE runId IN (SELECT id FROM runs WHERE sessionId = :sessionId)")
    suspend fun getMediaCountForSession(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM media WHERE runId = :runId")
    suspend fun getMediaCountForRun(runId: Long): Int
}
