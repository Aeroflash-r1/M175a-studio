package com.example.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Interface to provide coroutine dispatchers for testing and injection purposes.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}
