package com.example.domain.model

/**
 * A wrapper class that handles data and its state.
 */
sealed class Resource<out T> {
    object Loading : Resource<Nothing>()
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val error: ErrorModel) : Resource<Nothing>()
}
