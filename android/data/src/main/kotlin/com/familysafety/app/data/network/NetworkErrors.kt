package com.familysafety.app.data.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Turns transport failures into specific, actionable messages instead of a generic "check your connection". */
object NetworkErrors {
    fun describe(e: IOException, host: String?): String {
        val where = host?.let { " ($it)" } ?: ""
        return when (e) {
            is UnknownHostException ->
                if (host != null) "Can't find the server$where. Check the address and your internet connection."
                else "Can't find the server. Check your internet connection and the server address (Server, on the sign-in screen)."
            is SocketTimeoutException, is InterruptedIOException -> "The server$where took too long to respond. Try again in a moment."
            is ConnectException, is NoRouteToHostException -> "The server$where refused the connection. Make sure it is running and reachable from this phone."
            is SSLPeerUnverifiedException, is SSLHandshakeException -> "The server's security certificate$where isn't trusted. A valid HTTPS certificate is required."
            is SSLException -> "A secure connection to the server$where couldn't be established."
            else -> "Unable to reach the server$where. Check your connection."
        }
    }
}
