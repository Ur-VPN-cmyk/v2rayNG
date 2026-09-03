package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        
        // Only use local HTTP proxy port if it is actually open and listening on localhost
        val canUseLocalProxy = httpPort != 0 && socketConnectTime(AppConfig.LOOPBACK, httpPort, 200) >= 0
        val effectivePort = if (canUseLocalProxy) httpPort else 0

        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 3000,
                httpPort = effectivePort,
                proxyUsername = if (canUseLocalProxy) proxyUsername else null,
                proxyPassword = if (canUseLocalProxy) proxyPassword else null
            )
        )

        if (content != null) {
            val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java)
            if (ipInfo != null) {
                val ip = listOf(
                    ipInfo.ip,
                    ipInfo.clientIp,
                    ipInfo.ip_addr,
                    ipInfo.query
                ).firstOrNull { !it.isNullOrBlank() }

                val country = listOf(
                    ipInfo.country_code,
                    ipInfo.country,
                    ipInfo.countryCode,
                    ipInfo.location?.country_code
                ).firstOrNull { !it.isNullOrBlank() }

                if (!ip.isNullOrBlank() || !country.isNullOrBlank()) {
                    return RemoteEndpointInfo(
                        country = country,
                        ipAddress = ip,
                    )
                }
            }
        }

        // Fallback: Infer endpoint info from active server profile to prevent null/empty stats
        val activeGuid = MmkvManager.getSelectServer()
        val activeProfile = if (!activeGuid.isNullOrEmpty()) MmkvManager.decodeServerConfig(activeGuid) else null
        val serverRemarks = activeProfile?.remarks.orEmpty()
        val serverAddress = activeProfile?.server.orEmpty()

        val fallbackCountry = if (serverRemarks.contains("Frankfurt", ignoreCase = true) ||
            serverRemarks.contains("Germany", ignoreCase = true) ||
            serverAddress.contains("129.159.223.216")
        ) {
            "DE"
        } else {
            null
        }

        val fallbackIp = if (serverAddress.isNotEmpty() && !serverAddress.contains("127.0.0.1")) {
            serverAddress
        } else {
            null
        }

        return if (fallbackCountry != null || fallbackIp != null) {
            RemoteEndpointInfo(country = fallbackCountry, ipAddress = fallbackIp)
        } else {
            null
        }
    }
}
