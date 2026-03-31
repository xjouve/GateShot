package com.gateshot.session.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gateshot.session.data.MediaEntity
import com.gateshot.session.data.RunEntity
import com.gateshot.session.data.SessionEntity

@Database(
    entities = [SessionEntity::class, RunEntity::class, MediaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GateShotDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}
