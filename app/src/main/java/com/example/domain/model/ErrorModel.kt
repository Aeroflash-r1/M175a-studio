package com.example.domain.model

/**
 * Represents a generic error within the domain layer.
 */
data class ErrorModel(
    val message: String,
    val cause: Throwable? = null,
    val code: Int? = null
)
