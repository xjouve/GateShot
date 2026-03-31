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
        // Capture
        cameraModule: com.gateshot.capture.camera.CameraFeatureModule,
        burstModule: com.gateshot.capture.burst.BurstFeatureModule,
        presetModule: com.gateshot.capture.preset.PresetFeatureModule,
        triggerModule: com.gateshot.capture.trigger.TriggerFeatureModule,
        trackingModule: com.gateshot.capture.tracking.TrackingFeatureModule,
        // Session
        sessionModule: com.gateshot.session.SessionFeatureModule,
        // Processing
        snowExposureModule: com.gateshot.processing.snow.SnowExposureModule,
        burstCullingModule: com.gateshot.processing.culling.BurstCullingModule,
        bibDetectionModule: com.gateshot.processing.bib.BibDetectionModule,
        autoClipModule: com.gateshot.processing.autoclip.AutoClipModule,
        exportModule: com.gateshot.processing.export.ExportModule,
        superResolutionModule: com.gateshot.processing.sr.SuperResolutionModule,
        // Coaching
        replayModule: com.gateshot.coaching.replay.ReplayFeatureModule,
        timingModule: com.gateshot.coaching.timing.TimingFeatureModule,
        annotationModule: com.gateshot.coaching.annotation.AnnotationFeatureModule
    ): Set<FeatureModule> {
        return setOf(
            cameraModule, burstModule, presetModule, triggerModule, trackingModule,
            sessionModule,
            snowExposureModule, burstCullingModule, bibDetectionModule, autoClipModule, exportModule, superResolutionModule,
            replayModule, timingModule, annotationModule
        )
    }
}
