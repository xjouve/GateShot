package com.gateshot.core.api

sealed class ApiResponse<out T> {
    data class Success<T>(val data: T) : ApiResponse<T>()
    data class Error(val code: Int, val message: String) : ApiResponse<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun dataOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun dataOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw ApiException(code, message)
    }

    companion object {
        fun <T> success(data: T) = Success(data)
        fun error(code: Int, message: String) = Error(code, message)
        fun modeNotActive() = Error(403, "MODE_NOT_ACTIVE")
        fun notFound(path: String) = Error(404, "Endpoint not found: $path")
        fun moduleError(module: String, cause: String) = Error(500, "Module '$module' error: $cause")
    }
}

class ApiException(val code: Int, override val message: String) : RuntimeException(message)
