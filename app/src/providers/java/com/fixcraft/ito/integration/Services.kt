package com.fixcraft.ito.integration

import android.content.Context
import com.fixcraft.ito.BuildConfig
import com.fixcraft.ito.provider.ItoProviderBackend
import com.fixcraft.ito.provider.ItoProviderNative
import com.fixcraft.ito.provider.ItoProviderServices
import com.fixcraft.ito.provider.ItoProviderSession
import com.fixcraft.ito.provider.ItoProviderSessions
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionServices
import com.fixcraft.ito.transport.OpenedService
import com.fixcraft.ito.transport.ChosenRoute
import com.fixcraft.ito.transport.SelectedService
import com.fixcraft.ito.transport.ServicePermit
import com.fixcraft.ito.transport.ServiceRefusal
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.consumer.ServiceRoute
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderKitValidator
import com.fixcraft.yume.provider.ProviderRegistration
import com.fixcraft.yume.provider.ProviderStore
import java.io.File

/** This entire owner, including AppAuth, is excluded from the minimal source set. */
fun createServices(context: Context, requireAccess: () -> Unit = {},
                   authorizationAccess: suspend () -> Unit = { requireAccess() }): ServiceController<ItoProviderSession> {
    val app = context.applicationContext
    val backend = ItoProviderBackend(app, requireAccess)
    val sessions = ItoProviderSessions(app)
    val registration = if (BuildConfig.PROVIDER_CLIENT_ID.isEmpty()) null else
        ProviderRegistration(app.packageName, BuildConfig.PROVIDER_CLIENT_ID, BuildConfig.PROVIDER_CALLBACK)
    return ServiceController(app, registration, "relay", ItoProviderServices(backend, sessions)) { connections ->
        val store = ProviderStore(File(app.noBackupFilesDir, "ito-provider-state"), backend)
        try {
            ProviderClient(app, registration, store,
                ProviderKitValidator(ItoProviderNative(sessions)), connections = connections,
                authorizationAccess = authorizationAccess)
        } catch (error: Throwable) { store.close(); throw error }
    }
}

private class ProviderSelection(val selected: ServiceSelection) : SelectedService {
    override val provider get() = selected.provider
    override val identity get() = selected.identity
}

private class ProviderRoute(val route: ServiceRoute) : ChosenRoute {
    override val provider get() = route.provider
}

private inline fun <T> authority(operation: () -> T): T = try { operation() }
catch (refusal: ProviderCodec.Refusal) { throw ServiceRefusal(refusal.code) }

/** The shared owner retains every schema, native validator, lease and revocation check. */
fun transportServices(owner: ServiceController<ItoProviderSession>): ConnectionServices = object : ConnectionServices {
    override fun selectManual() = authority { owner.selectManual() }
    override fun selectedForRestart(): ChosenRoute? = authority {
        owner.selectedForRestart()?.let(::ProviderRoute)
    }
    override suspend fun prepare(route: ChosenRoute, renew: Boolean): SelectedService = authority {
        ProviderSelection(owner.prepare((route as? ProviderRoute)?.route ?: throw ServiceRefusal("binding"), renew))
    }
    override fun renewAfter(selection: SelectedService): Long? = authority {
        owner.renewAfter((selection as? ProviderSelection)?.selected ?: throw ServiceRefusal("binding"))
    }
    override suspend fun open(selection: SelectedService): OpenedService = authority {
        val selected = (selection as? ProviderSelection)?.selected ?: throw ServiceRefusal("binding")
        val opened = owner.open(selected)
        object : OpenedService {
            override val config get() = opened.session.config
            override val directory get() = opened.session.directory
            override val permit = object : ServicePermit {
                override fun valid() = opened.permit.valid()
            }
            override fun close() = opened.close()
        }
    }
}

// UI and qualification callers provide the actual shared owner explicitly. The
// extensions below keep selection changes inside ConnectionController's lane.
fun ConnectionController.useProvider(owner: ServiceController<ItoProviderSession>, provider: String) =
    changeAuthority { authority { owner.useProvider(provider) } }

/** Records the chosen server. A relay already running on this provider reconnects to it at once. */
fun ConnectionController.chooseServer(owner: ServiceController<ItoProviderSession>, provider: String, service: String, server: String) =
    changeAuthority(provider, move = true) { authority { owner.choose(provider, service, server)?.join() } }
