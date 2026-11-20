package com.familysafety.app.data.server

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retrofit's base URL is fixed when the client is built; this interceptor re-targets every request to the
 * server the user configured, preserving the API path, query and any path prefix of the configured URL.
 */
class BaseUrlInterceptor(private val buildBase: HttpUrl, private val target: () -> HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val rewritten = rewrite(request.url, buildBase, target())
        return chain.proceed(if (rewritten == request.url) request else request.newBuilder().url(rewritten).build())
    }

    companion object {
        fun rewrite(url: HttpUrl, buildBase: HttpUrl, target: HttpUrl): HttpUrl {
            if (url.host != buildBase.host || url.port != buildBase.port || url.scheme != buildBase.scheme) return url
            if (target == buildBase) return url
            val relative = url.encodedPath.removePrefix(buildBase.encodedPath).trimStart('/')
            return target.newBuilder()
                .encodedPath(target.encodedPath.trimEnd('/') + "/" + relative)
                .encodedQuery(url.encodedQuery)
                .build()
        }
    }
}
