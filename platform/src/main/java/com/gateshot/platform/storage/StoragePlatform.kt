package com.gateshot.platform.storage

import java.io.File

interface StoragePlatform {
    fun getAppStorageRoot(): File
    fun getAvailableSpaceBytes(): Long
    fun getTotalSpaceBytes(): Long
    fun getSessionDir(eventName: String, date: String, discipline: String): File
    fun getRunDir(sessionDir: File, runNumber: Int): File
    fun getCacheDir(): File
    fun getThumbnailDir(): File
}
