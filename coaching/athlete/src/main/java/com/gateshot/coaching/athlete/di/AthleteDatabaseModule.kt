package com.gateshot.coaching.athlete.di

import android.content.Context
import androidx.room.Room
import com.gateshot.coaching.athlete.db.AthleteDatabase
import com.gateshot.coaching.athlete.db.AthleteDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AthleteDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AthleteDatabase {
        return Room.databaseBuilder(context, AthleteDatabase::class.java, "gateshot_athletes.db").build()
    }

    @Provides
    fun provideAthleteDao(database: AthleteDatabase): AthleteDao = database.athleteDao()
}
