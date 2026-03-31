package com.gateshot.platform.di

import com.gateshot.platform.camera.CameraPlatform
import com.gateshot.platform.camera.CameraXPlatform
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {

    @Binds
    abstract fun bindCameraPlatform(impl: CameraXPlatform): CameraPlatform
}
