package com.fixcraft.ito.call

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One platform device lifetime, including partial startup. All entry points
 * and callbacks use the controller dispatcher. The owner outlives Service
 * destruction until startup and device cleanup have both settled.
 */
internal class AudioDeviceOwner<T : AutoCloseable>(
    controller: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val create: () -> T,
    private val startDevice: (T) -> Unit,
    private val ready: (T) -> Unit,
    private val failed: () -> Unit,
    private val closed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + controller)
    private var device: T? = null
    private var opening: Job? = null
    private var closing: Job? = null
    private var closeRequested = false

    fun start() {
        check(opening == null && !closeRequested)
        val startup = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val candidate = create().also { device = it }
                withContext(io) { startDevice(candidate) }
                if (!closeRequested) ready(candidate)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                try { if (!closeRequested) failed() } finally { close() }
            }
        }
        opening = startup
        startup.start()
    }

    fun close(): Job {
        closing?.let { return it }
        closeRequested = true
        val startup = opening
        startup?.cancel()
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(NonCancellable) {
                    // Cancellation cannot interrupt synchronous Android setup.
                    // Its borrower must return before the device can close.
                    startup?.join()
                    val candidate = device
                    device = null
                    withContext(io) { candidate?.close() }
                }
            } catch (_: Exception) {
                failed()
            } finally {
                try { closed() } finally { scope.cancel() }
            }
        }.also { closing = it; it.start() }
    }
}
