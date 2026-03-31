package com.gateshot.core.module

enum class Severity { INFO, WARNING, DEGRADED, CRITICAL }

data class ModuleHealth(
    val moduleName: String,
    val status: Status,
    val message: String? = null
) {
    enum class Status { OK, DEGRADED, ERROR, NOT_LOADED }
}
