package com.fixcraft.ito.transport

import java.io.File

/** Application authority presented to the transport owner. No enrollment types cross this seam. */
interface ConnectionServices {
    fun selectManual()
    fun selectedForRestart(): SelectedService?
    suspend fun open(selection: SelectedService): OpenedService
}

interface SelectedService {
    val provider: String
    val identity: String
}

interface ServicePermit {
    /** Captured authority remains independent of screen locking and later UI selection. */
    fun valid(): Boolean
}

interface OpenedService : AutoCloseable {
    val config: ByteArray
    val directory: File
    val permit: ServicePermit
}

/** Fixed local codes only, never peer-supplied exception text. */
class ServiceRefusal(val code: String) : Exception("Service authority refused")
