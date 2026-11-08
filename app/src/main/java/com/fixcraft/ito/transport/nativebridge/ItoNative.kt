package com.fixcraft.ito.transport.nativebridge

/** Only libyume's typed status and a fixed operation name cross this boundary. */
class NativeFailure(val code: Int, val operation: String) :
    RuntimeException("$operation failed ($code)")

data class NativeKitFile(val path: String, val bytes: ByteArray, val executable: Boolean)

/** Payload bytes are owned by the caller, which clears them after use. */
data class NativeEcho(val bytes: ByteArray, val peerFingerprint: ByteArray)

class NativeStatus(val values: LongArray, val peerFingerprint: ByteArray) {
    val state: Int get() = values[0].toInt()
    val session: Int get() = values[1].toInt()
    val lastFailure: Int get() = values[2].toInt()
    val sessions: Long get() = values[3]
    val bytesSent: Long get() = values[4]
    val bytesReceived: Long get() = values[5]
    val recordBytesSent: Long get() = values[6]
    val recordBytesReceived: Long get() = values[7]
    val connectedMs: Long get() = values[8]
    val retryMs: Long get() = values[9]
    val failedAttempts: Int get() = values[10].toInt()
}

/** Called on a native worker before connect. A refusal must not use another Network. */
fun interface SocketBinder {
    fun bind(fd: Int): Boolean
}

/**
 * Ito's adapter to public ABI v1. A handle has one Kotlin owner. Start and one
 * echo operation may overlap stop/status. Destroy runs only after every worker
 * borrowing that handle has joined. Endpoints have no device and never retry
 * automatically. Kotlin owns every explicit replacement attempt.
 */
object ItoNative {
    init { System.loadLibrary("itojni") }

    const val OK = 0
    const val CANCELLED = -11
    const val CLIENT = 1
    const val ACTIVE = 1
    const val ENDED = 2
    const val DESTINATION_NONE = 0
    const val DESTINATION_HOSTNAME = 1
    const val DESTINATION_IPV4 = 2
    const val DESTINATION_IPV6 = 3

    external fun openKit(sealed: ByteArray, code: ByteArray): Array<NativeKitFile>
    external fun checkConfig(config: ByteArray): Int
    external fun validate(config: ByteArray, baseDir: String): ByteArray
    external fun create(config: ByteArray, baseDir: String, binder: SocketBinder): Long
    external fun start(handle: Long, timeoutMs: Int): Int
    external fun stop(handle: Long): Int
    external fun status(handle: Long): NativeStatus
    external fun destroy(handle: Long)
    external fun streamEcho(
        handle: Long, service: String, destinationType: Int,
        destination: String, port: Int, payload: ByteArray
    ): NativeEcho
    external fun packetEcho(
        handle: Long, service: String, destinationType: Int,
        destination: String, port: Int, payload: ByteArray
    ): NativeEcho

    /** A named-service stream lent to the call core. Close it after the call run returned. */
    external fun openCallStream(handle: Long, service: String): Long
    /** The stream's verified server fingerprint: the call's relay authority. */
    external fun callStreamFingerprint(stream: Long): ByteArray
    external fun closeCallStream(stream: Long)

    /** Runtime/config/endpoint/kit/global callback/stream/packet owners, for lifecycle checks. */
    external fun liveHandles(): LongArray
}
