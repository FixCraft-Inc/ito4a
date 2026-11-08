package com.fixcraft.ito.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AudioDeviceOwnerTest {
    @Test fun shutdownWaitsForStartupBorrowerAndClosesExactlyOnce() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { controller ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val borrowing = AtomicBoolean(false)
            val overlappingClose = AtomicBoolean(false)
            val closed = AtomicInteger()
            val ready = AtomicBoolean(false)
            val failed = AtomicBoolean(false)
            val released = CompletableDeferred<Unit>()
            val device = AutoCloseable {
                overlappingClose.set(borrowing.get())
                closed.incrementAndGet()
            }
            val owner = AudioDeviceOwner(controller, Dispatchers.IO, { device }, {
                borrowing.set(true)
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                borrowing.set(false)
            }, { ready.set(true) }, { failed.set(true) }, { released.complete(Unit) })
            try {
                withContext(controller) { owner.start() }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val first = withContext(controller) { owner.close() }
                assertSame(first, withContext(controller) { owner.close() })
                withContext(controller) { Unit }
                assertFalse(released.isCompleted)
                assertEquals(0, closed.get())
                resume.countDown()
                withTimeout(5_000) { released.await() }
                assertFalse(overlappingClose.get())
                assertEquals(1, closed.get())
                assertFalse(ready.get())
                assertFalse(failed.get())
            } finally {
                resume.countDown()
                withContext(controller) { owner.close() }
                withTimeout(5_000) { released.await() }
            }
        }
    }

    @Test fun failedPartialStartupRetainsDeviceUntilCleanup() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { controller ->
            val closed = AtomicInteger()
            val failures = AtomicInteger()
            val released = CompletableDeferred<Unit>()
            val owner = AudioDeviceOwner(controller, Dispatchers.IO,
                { AutoCloseable { closed.incrementAndGet() } },
                { throw IllegalStateException("device setup refused") },
                { fail("Failed device was published") }, { failures.incrementAndGet() },
                { released.complete(Unit) })
            withContext(controller) { owner.start() }
            withTimeout(5_000) { released.await() }
            assertEquals(1, failures.get())
            assertEquals(1, closed.get())
        }
    }
}
