package com.fixcraft.ito.transport

import java.io.File

/** Application authority presented to the transport owner. No enrollment types cross this seam. */
interface ConnectionServices {
    fun selectManual()
    /** The selected provider route, or null for manual use. It is not authority until [prepare]. */
    fun selectedForRestart(): ChosenRoute?
    /**
     * Acquires the route's lease for its chosen server. A current lease is
     * returned without the network. A starting run ([renew] false) takes a live
     * lease even once it is due, so a blocked provider API cannot stop it, and a
     * running one renews it. A renewal that fails for now returns the live lease.
     */
    suspend fun prepare(route: ChosenRoute, renew: Boolean): SelectedService
    suspend fun open(selection: SelectedService): OpenedService
    /** When [selection]'s lease renews without a person, or null if it never does. */
    fun renewal(selection: SelectedService): ServiceRenewal?
}

interface ChosenRoute {
    val provider: String
}

interface SelectedService {
    val provider: String
    val identity: String
}

interface ServicePermit {
    /** Captured authority remains independent of screen locking and later UI selection. */
    fun valid(): Boolean
}

interface ServiceRenewal {
    /** Judged on the provider's trusted time, never the device's wall clock, and cheap enough to poll. */
    fun due(): Boolean
}

interface OpenedService : AutoCloseable {
    val config: ByteArray
    val directory: File
    val permit: ServicePermit
}

/** Fixed local codes only, never peer-supplied exception text. */
class ServiceRefusal(val code: String) : Exception("Service authority refused")
