package com.ipvio.stream

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.GzipSource
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for the whole app. Derived clients (other timeouts) share
 * the connection pool and threads instead of each area creating its own – saves memory on weak devices.
 *
 * MR TV 2: every request without its own User-Agent gets [Rebrand.USER_AGENT], and replies are read like in
 * MR TV 1.3 ([SafeGzip]): some panels label plain JSON as gzip, which OkHttp's built-in decoder rejects.
 */
object Net {
    private val userAgent = Interceptor { chain ->
        val r = chain.request()
        chain.proceed(if (r.header("User-Agent") == null) r.newBuilder().header("User-Agent", Rebrand.USER_AGENT).build() else r)
    }

    val base: OkHttpClient = SafeGzip.install(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(userAgent)
    ).build()

    fun withTimeouts(connectSec: Long, readSec: Long): OkHttpClient =
        base.newBuilder().connectTimeout(connectSec, TimeUnit.SECONDS).readTimeout(readSec, TimeUnit.SECONDS).build()
}

/** Ported from MR TV 1.3 (core SafeGzip): tolerates panels that label plain JSON as gzip, without buffering whole replies. */
object SafeGzip {
    private val identity = Interceptor { chain ->
        // An explicit encoding switches off OkHttp's strict transparent gzip decoder.
        val r = chain.request()
        chain.proceed(if (r.header("Accept-Encoding") == null) r.newBuilder().header("Accept-Encoding", "identity").build() else r)
    }
    private val decode = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        try {
            decode(response)
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    /** Idempotent: derived clients already inherit these interceptors. */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        if (identity !in interceptors()) addInterceptor(identity)
        if (decode !in networkInterceptors()) addNetworkInterceptor(decode)
    }

    private fun decode(response: Response): Response {
        if (!response.header("Content-Encoding").equals("gzip", ignoreCase = true)) return response
        val body = response.body ?: return response
        val source = body.source()
        val gzip = source.request(2) && (source.buffer[0].toInt() and 0xff) == 0x1f &&
            (source.buffer[1].toInt() and 0xff) == 0x8b
        val result = response.newBuilder().removeHeader("Content-Encoding")
        if (gzip) {
            result.removeHeader("Content-Length")
                .body(GzipSource(source).buffer().asResponseBody(body.contentType(), -1))
        }
        return result.build()
    }
}
