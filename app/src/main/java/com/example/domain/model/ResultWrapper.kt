package com.example.domain.model

/**
 * Represents the result of an operation that doesn't necessarily have a state (like loading),
 * but rather just success or failure.
 */
sealed class ResultWrapper<out T> {
    data class Success<out T>(val value: T) : ResultWrapper<T>()
    data class Failure(val error: ErrorModel) : ResultWrapper<Nothing>()
}
