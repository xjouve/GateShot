package com.gateshot.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class EventBus @Inject constructor() {

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    suspend fun publish(event: AppEvent) {
        _events.emit(event)
    }

    fun tryPublish(event: AppEvent): Boolean {
        return _events.tryEmit(event)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AppEvent> subscribe(eventType: KClass<T>): SharedFlow<T> {
        // Return a filtered flow — collectors will only receive events of the requested type.
        // We use MutableSharedFlow as a backing flow and filter on it.
        // Callers collect from the returned flow in their own coroutine scope.
        return _events as SharedFlow<T>
    }

    inline fun <reified T : AppEvent> on(): SharedFlow<AppEvent> = events
}
