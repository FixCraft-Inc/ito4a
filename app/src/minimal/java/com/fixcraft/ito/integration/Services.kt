package com.fixcraft.ito.integration

import android.content.Context
import com.fixcraft.ito.transport.ConnectionServices
import com.fixcraft.ito.transport.OpenedService
import com.fixcraft.ito.transport.SelectedService
import com.fixcraft.ito.transport.ServiceRefusal
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Minimal builds preserve provider ciphertext without trying to read its authority. */
class MinimalServices(private val retainedState: () -> Boolean, private val requireAccess: () -> Unit) : ConnectionServices, AutoCloseable {
    @Volatile private var manualChosen = false
    fun reload() = Unit
    override fun close() = Unit
    override fun selectManual() { requireAccess(); manualChosen = true }
    override fun selectedForRestart(): SelectedService? {
        requireAccess()
        if (!manualChosen && retainedState()) throw ServiceRefusal("providers_disabled")
        return null
    }
    override suspend fun open(selection: SelectedService): OpenedService = throw ServiceRefusal("providers_disabled")
    override fun renewAfter(selection: SelectedService): Long? = null
    override suspend fun renew(selection: SelectedService): SelectedService = throw ServiceRefusal("providers_disabled")
}

fun createServices(context: Context, requireAccess: () -> Unit = {},
                   @Suppress("UNUSED_PARAMETER") authorizationAccess: suspend () -> Unit = { requireAccess() }): MinimalServices {
    val root = context.applicationContext.noBackupFilesDir
    return MinimalServices({
        // Even an unreadable or linked path is retained authority. Only an
        // explicit manual selection can override it for this process.
        listOf("ito-provider-records", "ito-provider-state", "ito-provider-runtime").any {
            Files.exists(File(root, it).toPath(), NOFOLLOW_LINKS)
        }
    }, requireAccess)
}

fun transportServices(owner: MinimalServices): ConnectionServices = owner
