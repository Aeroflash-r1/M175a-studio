package com.example.core.logging

/**
 * Core logging abstraction for M175a Studio.
 * Supports standard debug/info/warning/error logs.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
