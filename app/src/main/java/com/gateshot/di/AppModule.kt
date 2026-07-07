package com.gateshot.di

import com.gateshot.core.module.FeatureModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @ElementsIntoSet
    fun provideFeatureModules(
        // Session
        sessionModule: com.gateshot.session.SessionFeatureModule,
        // Processing
        autoClipModule: com.gateshot.processing.autoclip.AutoClipModule,
        exportModule: com.gateshot.processing.export.ExportModule,
        // Coaching
        replayModule: com.gateshot.coaching.replay.ReplayFeatureModule,
        timingModule: com.gateshot.coaching.timing.TimingFeatureModule,
        annotationModule: com.gateshot.coaching.annotation.AnnotationFeatureModule,
        athleteModule: com.gateshot.coaching.athlete.AthleteFeatureModule,
        poseModule: com.gateshot.coaching.pose.PoseEstimationModule
    ): Set<FeatureModule> {
        return setOf(
            sessionModule,
            autoClipModule, exportModule,
            replayModule, timingModule, annotationModule, athleteModule, poseModule
        )
    }
}
