package com.tsfdroid.ai.di

import org.junit.Test
import org.junit.Assert.assertEquals
import okhttp3.OkHttpClient

class AppModuleTest {
    @Test
    fun `shared HTTP client keeps fast connects and streaming-safe read limits`() {
        // v1.1.1: the old 15s read timeout killed SSE streams whenever a
        // reasoning model paused >15s between chunks — truncated answers and
        // MALFORMED_RESPONSE cards in the field. Read timeout is per-read,
        // not per-call, so a large value only protects long streams; the
        // connect limit stays tight for snappy failures on dead hosts.
        val client: OkHttpClient = AppModule.provideOkHttpClient()
        assertEquals("connect timeout", 15000, client.connectTimeoutMillis)
        assertEquals("read timeout", 300000, client.readTimeoutMillis)
        assertEquals("write timeout", 60000, client.writeTimeoutMillis)
    }
}
