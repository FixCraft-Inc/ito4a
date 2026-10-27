package com.fixcraft.ito

import android.app.Application
import com.fixcraft.ito.provider.ItoProviderBackend
import com.fixcraft.ito.provider.ItoProviderNative
import com.fixcraft.ito.provider.ItoProviderServices
import com.fixcraft.ito.provider.ItoProviderSessions
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderKitValidator
import com.fixcraft.yume.provider.ProviderRegistration
import com.fixcraft.yume.provider.ProviderStore
import java.io.File

class ItoApplication : Application() {
    private val serviceOwner = lazy {
        val backend = ItoProviderBackend(this)
        val sessions = ItoProviderSessions(this)
        val registration = if (BuildConfig.PROVIDER_CLIENT_ID.isEmpty()) null else
            ProviderRegistration(packageName, BuildConfig.PROVIDER_CLIENT_ID, BuildConfig.PROVIDER_CALLBACK)
        ServiceController(this, registration, "relay", ItoProviderServices(backend, sessions)) { connections ->
            val store = ProviderStore(File(noBackupFilesDir, "ito-provider-state"), backend)
            try {
                ProviderClient(this, registration, store, ProviderKitValidator(ItoProviderNative(sessions)), connections = connections)
            } catch (error: Throwable) {
                store.close()
                throw error
            }
        }
    }
    val services get() = serviceOwner.value
    val connections: ConnectionController by lazy { ConnectionController(this, services = services) }

    override fun onTerminate() {
        if (serviceOwner.isInitialized()) serviceOwner.value.close()
        super.onTerminate()
    }
}
