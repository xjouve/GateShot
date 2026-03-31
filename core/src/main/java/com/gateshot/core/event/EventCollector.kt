package com.gateshot.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

inline fun <reified T : AppEvent> EventBus.collect(
    scope: CoroutineScope,
    crossinline handler: suspend (T) -> Unit
) {
    scope.launch {
        events.filterIsInstance<T>().collect { handler(it) }
    }
}
