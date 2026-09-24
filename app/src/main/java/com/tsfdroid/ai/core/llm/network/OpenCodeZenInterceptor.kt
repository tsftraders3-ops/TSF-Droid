package com.tsfdroid.ai.core.llm.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Outbound request shaping for the OpenCode Zen keyless endpoint.
 *
 * OpenCode Zen's free tier does not issue API keys: requests carrying an
 * Authorization header are rejected outright, and unidentified clients are
 * deprioritized. This interceptor strips any Authorization header that leaked
 * from a shared OkHttpClient builder and stamps the client identification the
 * endpoint expects, so TSF Droid can ride the free tier without credentials.
 */
class OpenCodeZenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.toString()
        val builder = original.newBuilder()

        if (url.contains("opencode.ai/zen")) {
            // OpenCode Zen free tier requires an empty Authorization header
            builder.removeHeader("Authorization")
            builder.header("User-Agent", "opencode/1.18.18 (Android; TSF Droid Agent)")
        }
        return chain.proceed(builder.build())
    }
}
