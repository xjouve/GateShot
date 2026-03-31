package com.gateshot.session.di

import android.content.Context
import androidx.room.Room
import com.gateshot.session.db.GateShotDatabase
import com.gateshot.session.db.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SessionDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GateShotDatabase {
        return Room.databaseBuilder(
            context,
            GateShotDatabase::class.java,
            "gateshot.db"
        ).build()
    }

    @Provides
    fun provideSessionDao(database: GateShotDatabase): SessionDao {
        return database.sessionDao()
    }
}
