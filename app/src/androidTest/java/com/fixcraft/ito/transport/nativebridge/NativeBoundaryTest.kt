package com.fixcraft.ito.transport.nativebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the packaged adapter and public libyume, without any VPN app. */
@RunWith(AndroidJUnit4::class)
class NativeBoundaryTest {
    private lateinit var entryOwners: LongArray

    @Before fun rememberOwners() {
        entryOwners = ItoNative.liveHandles()
        assertEquals(7, entryOwners.size)
    }

    @After fun allAcquisitionsReleased() {
        assertArrayEquals(entryOwners, ItoNative.liveHandles())
    }

    @Test fun strictParserDoesNotLoadCredentialFiles() {
        assertEquals(ItoNative.CLIENT, ItoNative.checkConfig(clientConfig.toByteArray()))
    }

    @Test fun malformedAndUnknownFieldsHaveTypedParseFailures() {
        failure(-9, "config-parse") { ItoNative.checkConfig("{".toByteArray()) }
        val untrusted = clientConfig.replace("\"schema\":1", "\"schema\":1,\"secret-marker\":true")
        val error = failure(-9, "config-parse") { ItoNative.checkConfig(untrusted.toByteArray()) }
        assertFalse(error.message.orEmpty().contains("secret-marker"))
    }

    @Test fun documentBoundsRejectBeforePublication() {
        failure(-1, "input-size") { ItoNative.checkConfig(byteArrayOf()) }
        failure(-1, "input-size") { ItoNative.checkConfig(ByteArray(1024 * 1024 + 1)) }
    }

    @Test fun missingCredentialsReleaseRuntimeAndConfiguration() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.absolutePath
        failure(-1, "credentials-validate") {
            ItoNative.validate(clientConfig.toByteArray(), directory)
        }
        failure(-1, "credentials-validate") {
            ItoNative.create(clientConfig.toByteArray(), directory, SocketBinder { false })
        }
    }

    @Test fun sealedAuthenticationFailurePublishesNoKit() {
        val code = "00000-00000-00000-00000-00000".toByteArray()
        val sealed = ByteArray(16 + 12 + 1024 + 16)
        try {
            failure(-8, "kit-open") { ItoNative.openKit(sealed, code) }
        } finally {
            code.fill(0)
            sealed.fill(0)
        }
    }

    @Test fun kitAndPayloadBoundsFailBeforeAnyHandleUse() {
        val code = ByteArray(25) { '0'.code.toByte() }
        try {
            failure(-1, "input-size") { ItoNative.openKit(byteArrayOf(), code) }
            failure(-1, "input-size") { ItoNative.openKit(ByteArray(2 * 1024 * 1024 + 1), code) }
            failure(-1, "input-size") {
                ItoNative.streamEcho(0, "echo", 0, "", 0, ByteArray(65537))
            }
            failure(-1, "input-size") {
                ItoNative.packetEcho(0, "echo", 0, "", 0, ByteArray(1025))
            }
        } finally {
            code.fill(0)
        }
    }

    @Test fun absentHandlesAndInvalidDeadlinesAreTyped() {
        failure(-1, "handle") { ItoNative.stop(0) }
        failure(-1, "handle") { ItoNative.status(0) }
        failure(-1, "handle") { ItoNative.destroy(0) }
        failure(-1, "start-deadline") { ItoNative.start(0, 0) }
        failure(-1, "start-deadline") { ItoNative.start(0, 300001) }
    }

    private fun failure(code: Int, operation: String, action: () -> Any?): NativeFailure {
        try {
            action()
        } catch (error: NativeFailure) {
            assertEquals(operation, error.operation)
            assertEquals(code, error.code)
            return error
        }
        throw AssertionError("Expected typed failure from $operation")
    }

    private val clientConfig = """
        {"schema":1,"role":"client","endpoint":{"host":"origin.example.com","port":443},
         "suite":{"id":"ytp1-tls13-h2","secure_channel":"tls13-native","front_door":"h2-web",
           "carrier":"h2-duplex","session":"ytp1-hybrid"},
         "credentials":{"composite_key":{"file":"credentials/absent-client.pem"},
           "access_psk":{"file":"credentials/absent-access.psk"},
           "admission_key":{"file":"credentials/absent-admission.key"},
           "server_trust":{"file":"credentials/absent-trust.pem"},
           "server_identity":{"file":"credentials/absent-server.pem"},
           "server_mlkem":{"file":"credentials/absent-mlkem.pub"}},
         "cover":{"profile":"chrome151-node24-v1"},
         "services":[{"name":"echo","kind":"stream","max_concurrent_streams":8}],
         "adapters":[],"limits":{"max_frame_bytes":262144,"max_streams":256,
           "max_queued_bytes":4194304,"max_pending_opens":64,"max_rekey_jobs":4,
           "max_control_messages":128,"max_packet_bytes":65535,"max_packet_batch":64}}
    """.trimIndent()
}
