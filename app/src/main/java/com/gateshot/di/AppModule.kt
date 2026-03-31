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
        cameraModule: com.gateshot.capture.camera.CameraFeatureModule,
        burstModule: com.gateshot.capture.burst.BurstFeatureModule,
        presetModule: com.gateshot.capture.preset.PresetFeatureModule,
        snowExposureModule: com.gateshot.processing.snow.SnowExposureModule
    ): Set<FeatureModule> {
        return setOf(cameraModule, burstModule, presetModule, snowExposureModule)
    }
}
