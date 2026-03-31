package com.gateshot.di

import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleLoader
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
        presetModule: com.gateshot.capture.preset.PresetFeatureModule
    ): Set<FeatureModule> {
        return setOf(cameraModule, presetModule)
    }
}
