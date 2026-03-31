package com.gateshot.session.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventName: String,
    val discipline: String,
    val date: String,                // ISO 8601: "2026-01-25"
    val locationLat: Double? = null,
    val locationLon: Double? = null,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val isActive: Boolean = true
)

@Entity(
    tableName = "runs",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val runNumber: Int,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val isActive: Boolean = true
)

@Entity(
    tableName = "media",
    foreignKeys = [ForeignKey(
        entity = RunEntity::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("runId"), Index("bibNumber"), Index("starRating")]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val type: MediaType,
    val fileUri: String,
    val thumbnailUri: String? = null,
    val bibNumber: Int? = null,
    val starRating: Int = 0,         // 0-5
    val isFlagged: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,    // For video
    val fileSizeBytes: Long = 0,
    val captureTimestamp: Long = System.currentTimeMillis(),
    val metadata: String? = null     // JSON blob for extra EXIF/XMP data
)

enum class MediaType {
    PHOTO,
    PHOTO_RAW,
    VIDEO,
    VIDEO_SLOMO,
    BURST_FRAME
}
