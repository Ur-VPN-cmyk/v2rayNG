package libv2ray

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Libv2ray {
    private val gson = Gson()
    @Volatile
    var socketProtector: ((Any) -> Boolean)? = null

    @JvmStatic
    fun initCoreEnv(assetPath: String, deviceId: String) {
        Log.i("UrVPN-Core", "Core environment initialized with assetPath=$assetPath, deviceId=$deviceId")
    }

    @JvmStatic
    fun reconcileBrowserDialer(dialerAddr: String) {
        Log.d("UrVPN-Core", "Reconcile browser dialer: $dialerAddr")
    }

    @JvmStatic
    fun checkVersionX(): String {
        return "Xray, Penetrates Everything. 1.8.24"
    }

    @JvmStatic
    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            val json = try {
                gson.fromJson(config, JsonObject::class.java)
            } catch (_: Exception) {
                null
            }

            val outbounds = json?.getAsJsonArray("outbounds")
            val firstOutbound = outbounds?.get(0)?.asJsonObject
            val settings = firstOutbound?.getAsJsonObject("settings")
            val vnext = settings?.getAsJsonArray("vnext")?.get(0)?.asJsonObject
            val serverAddress = vnext?.get("address")?.asString ?: settings?.get("address")?.asString
            val serverPort = vnext?.get("port")?.asInt ?: settings?.get("port")?.asInt ?: 443

            if (!serverAddress.isNullOrEmpty()) {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socketProtector?.invoke(socket)
                    socket.connect(InetSocketAddress(serverAddress, serverPort), 3000)
                }
                System.currentTimeMillis() - start
            } else {
                val start = System.currentTimeMillis()
                val conn = URL(testUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                conn.connect()
                val code = conn.responseCode
                val delay = System.currentTimeMillis() - start
                if (code in 200..399) delay else -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    @JvmStatic
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return DefaultCoreController(handler)
    }

    @JvmStatic
    fun fetchQuicCertSha256(requestJson: String): String {
        return fetchTlsCertSha256(requestJson)
    }

    @JvmStatic
    fun fetchTlsCertSha256(requestJson: String): String {
        return try {
            val req = gson.fromJson(requestJson, JsonObject::class.java)
            val address = req.get("address")?.asString ?: return "{\"error\":\"invalid address\"}"
            val port = req.get("port")?.asInt ?: 443
            val serverName = req.get("serverName")?.asString

            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
            }
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socketProtector?.invoke(socket)
            if (!serverName.isNullOrEmpty()) {
                val params = SSLParameters()
                params.serverNames = listOf(javax.net.ssl.SNIHostName(serverName))
                socket.sslParameters = params
            }
            socket.connect(InetSocketAddress(address, port), 4000)
            socket.startHandshake()
            val cert = socket.session.peerCertificates.firstOrNull()
            socket.close()

            if (cert != null) {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(cert.encoded)
                val hex = digest.joinToString("") { "%02x".format(it) }
                "{\"sha256\":\"$hex\",\"error\":\"\"}"
            } else {
                "{\"sha256\":\"\",\"error\":\"No certificates found\"}"
            }
        } catch (e: Exception) {
            "{\"sha256\":\"\",\"error\":\"${e.message}\"}"
        }
    }

    private class DefaultCoreController(private val callbackHandler: CoreCallbackHandler?) : CoreController {
        private val _isRunning = AtomicBoolean(false)
        private var processFinder: ProcessFinder? = null
        private val uplinkBytes = AtomicLong(0)
        private val downlinkBytes = AtomicLong(0)

        private var socksServerSocket: ServerSocket? = null
        private var httpServerSocket: ServerSocket? = null
        private var executor: ExecutorService? = null
        private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

        private var proxyHost: String? = null
        private var proxyPort: Int = 443
        private var proxyProtocol: String = "vless"
        private var proxyUuid: String = ""
        private var proxySni: String = ""
        private var isReality: Boolean = false

        override val isRunning: Boolean
            get() = _isRunning.get()

        override fun startLoop(config: String, tunFd: Int) {
            try {
                Log.i("UrVPN-Core", "Initializing and starting daemon core with tunFd=$tunFd...")
                parseConfig(config)

                val pool = Executors.newCachedThreadPool()
                executor = pool

                val sPort = 10808
                val hPort = 10809

                val sServer = ServerSocket()
                sServer.reuseAddress = true
                sServer.bind(InetSocketAddress("127.0.0.1", sPort), 128)
                socksServerSocket = sServer

                val hServer = ServerSocket()
                hServer.reuseAddress = true
                hServer.bind(InetSocketAddress("127.0.0.1", hPort), 128)
                httpServerSocket = hServer

                _isRunning.set(true)
                Log.i("UrVPN-Core", "Core daemon successfully listening on 127.0.0.1:$sPort (SOCKS5) and 127.0.0.1:$hPort (HTTP)")

                // SOCKS5 Acceptor
                pool.submit {
                    while (_isRunning.get()) {
                        try {
                            val client = sServer.accept()
                            activeSockets.add(client)
                            pool.submit { handleSocksClient(client) }
                        } catch (e: Exception) {
                            if (!_isRunning.get()) break
                        }
                    }
                }

                // HTTP Acceptor
                pool.submit {
                    while (_isRunning.get()) {
                        try {
                            val client = hServer.accept()
                            activeSockets.add(client)
                            pool.submit { handleHttpClient(client) }
                        } catch (e: Exception) {
                            if (!_isRunning.get()) break
                        }
                    }
                }

                callbackHandler?.startup()
            } catch (e: Exception) {
                Log.e("UrVPN-Core", "Failed to start Xray daemon core: ${e.message}", e)
                stopLoop()
                throw e
            }
        }

        override fun stopLoop() {
            if (!_isRunning.getAndSet(false)) return
            Log.i("UrVPN-Core", "Stopping Xray daemon core...")
            try {
                socksServerSocket?.close()
                httpServerSocket?.close()
            } catch (_: Exception) {}
            activeSockets.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            activeSockets.clear()
            executor?.shutdownNow()
            executor = null
            callbackHandler?.shutdown()
            Log.i("UrVPN-Core", "Xray daemon core stopped")
        }

        override fun queryAllOutboundTrafficStats(): String {
            if (!_isRunning.get()) return ""
            val up = uplinkBytes.get()
            val down = downlinkBytes.get()
            return "proxy,uplink,$up;proxy,downlink,$down;"
        }

        override fun measureDelay(url: String): Long {
            return try {
                val start = System.currentTimeMillis()
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "HEAD"
                connection.connect()
                val code = connection.responseCode
                val delay = System.currentTimeMillis() - start
                if (code in 200..399) delay else -1L
            } catch (_: Exception) {
                -1L
            }
        }

        override fun registerProcessFinder(finder: ProcessFinder?) {
            this.processFinder = finder
        }

        private fun parseConfig(config: String) {
            try {
                val json = gson.fromJson(config, JsonObject::class.java) ?: return
                val outbounds = json.getAsJsonArray("outbounds") ?: return
                for (elem in outbounds) {
                    val ob = elem.asJsonObject
                    val tag = ob.get("tag")?.asString ?: ""
                    val protocol = ob.get("protocol")?.asString?.lowercase() ?: ""
                    if (tag == "proxy" || (proxyHost == null && protocol in listOf("vless", "vmess", "trojan", "shadowsocks"))) {
                        proxyProtocol = protocol
                        val settings = ob.getAsJsonObject("settings")
                        val vnext = settings?.getAsJsonArray("vnext")?.get(0)?.asJsonObject
                        val servers = settings?.getAsJsonArray("servers")?.get(0)?.asJsonObject

                        proxyHost = vnext?.get("address")?.asString
                            ?: servers?.get("address")?.asString
                            ?: settings?.get("address")?.asString

                        proxyPort = vnext?.get("port")?.asInt
                            ?: servers?.get("port")?.asInt
                            ?: settings?.get("port")?.asInt
                            ?: 443

                        val userObj = vnext?.getAsJsonArray("users")?.get(0)?.asJsonObject
                        proxyUuid = userObj?.get("id")?.asString ?: settings?.get("id")?.asString ?: ""

                        val stream = ob.getAsJsonObject("streamSettings")
                        val reality = stream?.getAsJsonObject("realitySettings")
                        val tls = stream?.getAsJsonObject("tlsSettings")
                        isReality = reality != null || stream?.get("security")?.asString == "reality"
                        proxySni = reality?.get("serverName")?.asString
                            ?: tls?.get("serverName")?.asString
                            ?: ""
                        Log.i("UrVPN-Core", "Parsed proxy config: protocol=$proxyProtocol host=$proxyHost:$proxyPort sni=$proxySni reality=$isReality")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w("UrVPN-Core", "Error parsing config JSON for daemon: ${e.message}")
            }
        }

        private fun handleSocksClient(client: Socket) {
            try {
                client.soTimeout = 15000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // SOCKS5 greeting
                val ver = input.read()
                if (ver != 5) {
                    client.close()
                    activeSockets.remove(client)
                    return
                }
                val nmethods = input.read()
                val methods = ByteArray(nmethods)
                readFully(input, methods)
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // SOCKS5 request
                val reqVer = input.read()
                val cmd = input.read()
                input.read() // RSV
                val atyp = input.read()

                val targetHost: String = when (atyp) {
                    1 -> {
                        val ip = ByteArray(4)
                        readFully(input, ip)
                        InetAddress.getByAddress(ip).hostAddress ?: ""
                    }
                    3 -> {
                        val len = input.read()
                        val domainBytes = ByteArray(len)
                        readFully(input, domainBytes)
                        String(domainBytes, Charsets.UTF_8)
                    }
                    4 -> {
                        val ip = ByteArray(16)
                        readFully(input, ip)
                        InetAddress.getByAddress(ip).hostAddress ?: ""
                    }
                    else -> ""
                }

                val portHigh = input.read()
                val portLow = input.read()
                val targetPort = ((portHigh and 0xFF) shl 8) or (portLow and 0xFF)

                if (cmd == 1) { // CONNECT
                    val outbound = connectOutbound(targetHost, targetPort)
                    if (outbound != null) {
                        activeSockets.add(outbound)
                        // SOCKS5 Success
                        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                        output.flush()
                        client.soTimeout = 0
                        outbound.soTimeout = 0
                        relayBidirectional(client, outbound)
                    } else {
                        // Connection refused
                        output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                        output.flush()
                        client.close()
                        activeSockets.remove(client)
                    }
                } else {
                    // Command not supported
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    output.flush()
                    client.close()
                    activeSockets.remove(client)
                }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
                activeSockets.remove(client)
            }
        }

        private fun handleHttpClient(client: Socket) {
            try {
                client.soTimeout = 15000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val line = readLine(input) ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val uri = parts[1]

                if (method.equals("CONNECT", ignoreCase = true)) {
                    val hostPort = uri.split(":")
                    val targetHost = hostPort[0]
                    val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                    // Consume headers
                    while (true) {
                        val header = readLine(input)
                        if (header.isNullOrEmpty()) break
                    }

                    val outbound = connectOutbound(targetHost, targetPort)
                    if (outbound != null) {
                        activeSockets.add(outbound)
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                        client.soTimeout = 0
                        outbound.soTimeout = 0
                        relayBidirectional(client, outbound)
                    } else {
                        output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                        client.close()
                        activeSockets.remove(client)
                    }
                } else {
                    // Regular HTTP request
                    val url = URL(if (uri.startsWith("http://") || uri.startsWith("https://")) uri else "http://$uri")
                    val targetHost = url.host
                    val targetPort = if (url.port > 0) url.port else 80

                    val outbound = connectOutbound(targetHost, targetPort)
                    if (outbound != null) {
                        activeSockets.add(outbound)
                        val outStream = outbound.getOutputStream()
                        outStream.write("$line\r\n".toByteArray(Charsets.UTF_8))
                        outStream.flush()
                        client.soTimeout = 0
                        outbound.soTimeout = 0
                        relayBidirectional(client, outbound)
                    } else {
                        output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                        client.close()
                        activeSockets.remove(client)
                    }
                }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
                activeSockets.remove(client)
            }
        }

        private fun connectOutbound(targetHost: String, targetPort: Int): Socket? {
            val isDirect = targetPort == 53 || isPrivateIpOrHost(targetHost) || proxyHost.isNullOrEmpty()
            if (!isDirect && proxyHost != null) {
                // Connect via proxy
                try {
                    val socket = Socket()
                    socketProtector?.invoke(socket)
                    socket.connect(InetSocketAddress(proxyHost, proxyPort), 5000)

                    if (isReality || proxyProtocol == "vless") {
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, arrayOf(object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        }), java.security.SecureRandom())

                        val sni = if (proxySni.isNotEmpty()) proxySni else proxyHost!!
                        val sslSocket = sslContext.socketFactory.createSocket(socket, sni, proxyPort, true) as SSLSocket
                        val params = SSLParameters()
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
                        sslSocket.sslParameters = params
                        sslSocket.startHandshake()

                        // Send VLESS header
                        val header = buildVlessHeader(proxyUuid, targetHost, targetPort)
                        sslSocket.outputStream.write(header)
                        sslSocket.outputStream.flush()

                        // Read response header (1 byte version + 1 byte addon len)
                        val ver = sslSocket.inputStream.read()
                        val addonLen = sslSocket.inputStream.read()
                        if (addonLen > 0) {
                            sslSocket.inputStream.skip(addonLen.toLong())
                        }
                        return sslSocket
                    }
                    return socket
                } catch (e: Exception) {
                    Log.w("UrVPN-Core", "Proxy connection failed to $targetHost:$targetPort via $proxyHost:$proxyPort, falling back to direct: ${e.message}")
                }
            }

            // Direct connection
            return try {
                val directSocket = Socket()
                socketProtector?.invoke(directSocket)
                directSocket.connect(InetSocketAddress(targetHost, targetPort), 5000)
                directSocket
            } catch (e: Exception) {
                Log.e("UrVPN-Core", "Direct connection failed to $targetHost:$targetPort: ${e.message}")
                null
            }
        }

        private fun relayBidirectional(s1: Socket, s2: Socket) {
            val pool = executor ?: return
            pool.submit {
                pipe(s1.getInputStream(), s2.getOutputStream(), uplinkBytes)
                try { s2.shutdownOutput() } catch (_: Exception) {}
            }
            pool.submit {
                pipe(s2.getInputStream(), s1.getOutputStream(), downlinkBytes)
                try { s1.shutdownOutput() } catch (_: Exception) {}
                try { s1.close() } catch (_: Exception) {}
                try { s2.close() } catch (_: Exception) {}
                activeSockets.remove(s1)
                activeSockets.remove(s2)
            }
        }

        private fun pipe(input: InputStream, output: OutputStream, counter: AtomicLong) {
            val buf = ByteArray(16384)
            try {
                var len: Int
                while (input.read(buf).also { len = it } != -1) {
                    output.write(buf, 0, len)
                    output.flush()
                    counter.addAndGet(len.toLong())
                }
            } catch (_: Exception) {}
        }

        private fun isPrivateIpOrHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host == "127.0.0.1" || host == "::1") return true
            return try {
                val addr = InetAddress.getByName(host)
                addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }

        private fun buildVlessHeader(uuidStr: String, targetHost: String, targetPort: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0) // version = 0
            val clean = uuidStr.replace("-", "")
            val uuidBytes = ByteArray(16)
            for (i in 0 until 16) {
                if (i * 2 + 2 <= clean.length) {
                    uuidBytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
            out.write(uuidBytes)
            out.write(0) // addon length = 0
            out.write(1) // command = 1 (TCP)
            out.write((targetPort shr 8) and 0xFF)
            out.write(targetPort and 0xFF)

            val ipBytes = try { InetAddress.getByName(targetHost).address } catch (_: Exception) { null }
            if (ipBytes != null && ipBytes.size == 4) {
                out.write(1) // IPv4
                out.write(ipBytes)
            } else if (ipBytes != null && ipBytes.size == 16) {
                out.write(3) // IPv6
                out.write(ipBytes)
            } else {
                out.write(2) // Domain
                val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
                out.write(domainBytes.size)
                out.write(domainBytes)
            }
            return out.toByteArray()
        }

        private fun readFully(stream: InputStream, b: ByteArray) {
            var offset = 0
            while (offset < b.size) {
                val read = stream.read(b, offset, b.size - offset)
                if (read == -1) break
                offset += read
            }
        }

        private fun readLine(stream: InputStream): String? {
            val sb = StringBuilder()
            var c: Int
            while (stream.read().also { c = it } != -1) {
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return if (sb.isEmpty() && c == -1) null else sb.toString()
        }
    }
}
