package com.tsfdroid.ai.actions

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 (run-107): the in-app HTTP fetch must be bounded by a HARD wall
 * clock. The gold-price E2E hang (both passes, 09:51:45 -> 10:02 with zero
 * activity) proved that HttpURLConnection's connect/read timeouts do not
 * bound the whole exchange — a pathologically slow or black-holed endpoint
 * could stall a plan step for many minutes. These tests pin the fix: a
 * server that accepts the connection but never answers is ABANDONED within
 * the requested budget.
 */
class BoundedHttpFetchTest {

    @Test(timeout = 30_000)
    fun `a server that accepts but never answers is abandoned within the budget`() = runBlocking {
        // A raw TCP socket that accepts and then never writes a byte — the
        // worst case: connect succeeds, so connectTimeout never fires, and
        // the response read blocks until the read timeout (12s) at the
        // earliest. The wall clock must cut it off much sooner.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept() /* hold the socket open, answer nothing */ }
                    .onFailure { return@thread }
            }
        }

        try {
            val startedAt = System.currentTimeMillis()
            val result = InformationActions.httpGetText(
                url = "http://127.0.0.1:${server.localPort}/stall",
                timeoutMs = 1_500
            )
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertNull("a stalling fetch must return null, not data", result)
            assertTrue(
                "the fetch must be abandoned within the 1.5s budget (+tolerance), " +
                    "took ${elapsedMs}ms",
                elapsedMs < 5_000
            )
        } finally {
            runCatching { server.close() }
            acceptor.interrupt()
        }
    }

    @Test(timeout = 30_000)
    fun `caller cancellation is never swallowed by the fetch bound`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept() }.onFailure { return@thread }
            }
        }

        try {
            // A generous wall clock, but the CALLER cancels long before it.
            val job: Job = launch(Dispatchers.IO) {
                InformationActions.httpGetText(
                    url = "http://127.0.0.1:${server.localPort}/cancel",
                    timeoutMs = 20_000
                )
            }
            Thread.sleep(300)
            job.cancel()
            // If cancellation were swallowed, join would hang until the
            // 20s wall clock; the test timeout would catch it.
            withTimeout(5_000) { job.join() }
            assertTrue("job joined after cancel", job.isCompleted)
        } finally {
            runCatching { server.close() }
            acceptor.interrupt()
        }
    }
}
