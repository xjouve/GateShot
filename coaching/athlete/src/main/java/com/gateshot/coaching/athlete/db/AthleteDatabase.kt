package com.gateshot.coaching.athlete.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gateshot.coaching.athlete.data.AthleteDrillEntity
import com.gateshot.coaching.athlete.data.AthleteEntity
import com.gateshot.coaching.athlete.data.AthleteErrorEntity
import com.gateshot.coaching.athlete.data.AthleteProgressEntity

@Database(
    entities = [
        AthleteEntity::class,
        AthleteErrorEntity::class,
        AthleteDrillEntity::class,
        AthleteProgressEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AthleteDatabase : RoomDatabase() {
    abstract fun athleteDao(): AthleteDao
}
