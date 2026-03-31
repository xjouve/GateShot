package com.gateshot.core.di

import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.api.EndpointRegistryContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    abstract fun bindEndpointRegistry(impl: EndpointRegistry): EndpointRegistryContract
}
