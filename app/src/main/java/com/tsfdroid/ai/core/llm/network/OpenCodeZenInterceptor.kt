package com.tsfdroid.ai.core.llm.network

import com.tsfdroid.ai.core.llm.providers.ZenIdentity
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Outbound request shaping for the OpenCode Zen endpoint.
 *
 * Reverse-engineered from the official OpenCode client (sst/opencode):
 * the endpoint grants anonymous ("keyless") access only to requests that
 * present the same provenance the CLI does — `Authorization: Bearer public`
 * plus the `x-opencode-*` identity headers and a 4-segment
 * `opencode/<channel>/<version>/<client>` User-Agent. v1.0.1 stripped the
 * Authorization header and sent a generic UA, which the server answers with
 * `403 FreeTierError` ("free tier can only be used from within OpenCode").
 *
 * When the user configures a real Zen key it replaces the anonymous one, but
 * the identity headers are always stamped: paid keys are validated against
 * them for session affinity and abuse tracking.
 */
class OpenCodeZenInterceptor(
    private val identity: IdentityProvider
) : Interceptor {

    /** Supplies per-process/per-request identity values. */
    fun interface IdentityProvider {
        fun current(): Snapshot
    }

    data class Snapshot(
        val project: String,
        val session: String,
        val request: String,
        val apiKey: String
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.host.endsWith("opencode.ai")) {
            return chain.proceed(original)
        }
        val snapshot = identity.current()
        val builder = original.newBuilder()
        for ((name, value) in ZenIdentity.identityHeaders(snapshot.project, snapshot.session, snapshot.request)) {
            builder.header(name, value)
        }
        builder.header("Authorization", "Bearer ${snapshot.apiKey}")
        return chain.proceed(builder.build())
    }
}
