package com.gateshot.coaching.athlete.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "athletes")
data class AthleteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val bibNumbers: String = "",          // Comma-separated: "7,14,23"
    val ageGroup: String = "",            // e.g., "U16", "U18", "Senior"
    val discipline: String = "",          // e.g., "SL,GS" or "ALL"
    val team: String = "",
    val notes: String = "",               // Coach's free-text notes
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "athlete_errors",
    foreignKeys = [ForeignKey(
        entity = AthleteEntity::class,
        parentColumns = ["id"],
        childColumns = ["athleteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("athleteId")]
)
data class AthleteErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val athleteId: Long,
    val patternType: String,              // e.g., "inside_hand_drop", "late_pressure", "back_seat"
    val description: String,
    val severity: String = "medium",      // low, medium, high
    val trend: String = "stable",         // improving, stable, regressing
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val occurrenceCount: Int = 1,
    val relatedMediaIds: String = ""      // Comma-separated media IDs
)

@Entity(
    tableName = "athlete_drills",
    foreignKeys = [ForeignKey(
        entity = AthleteEntity::class,
        parentColumns = ["id"],
        childColumns = ["athleteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("athleteId")]
)
data class AthleteDrillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val athleteId: Long,
    val drillName: String,
    val category: String,                 // "edge_work", "pole_plant", "transition", "body_position", "line_choice"
    val description: String = "",
    val referenceMediaId: Long? = null,
    val assignedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(
    tableName = "athlete_progress",
    foreignKeys = [ForeignKey(
        entity = AthleteEntity::class,
        parentColumns = ["id"],
        childColumns = ["athleteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("athleteId")]
)
data class AthleteProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val athleteId: Long,
    val date: String,                     // ISO 8601
    val sessionId: Long? = null,
    val metric: String,                   // e.g., "gate_12_consistency", "knee_angle_avg", "split_time_gs"
    val value: Float,
    val notes: String = "",
    val mediaId: Long? = null             // Before/after reference frame
)
