package com.gateshot.core.api

import com.gateshot.core.mode.AppMode

interface ApiEndpoint<Request, Response> {
    val path: String
    val module: String
    val requiredMode: AppMode?
    val version: Int get() = 1

    suspend fun handle(request: Request): ApiResponse<Response>
}

data class EndpointDescriptor(
    val path: String,
    val module: String,
    val requiredMode: AppMode?,
    val version: Int
)
