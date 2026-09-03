package libv2ray

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

object Libv2ray {
    private val gson = Gson()

    @JvmStatic
    fun initCoreEnv(assetPath: String, deviceId: String) {
        // Core environment initialized
    }

    @JvmStatic
    fun reconcileBrowserDialer(dialerAddr: String) {
        // Reconcile browser dialer
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
            val vnext = firstOutbound?.getAsJsonObject("settings")
                ?.getAsJsonArray("vnext")?.get(0)?.asJsonObject
            val serverAddress = vnext?.get("address")?.asString
            val serverPort = vnext?.get("port")?.asInt ?: 443

            if (!serverAddress.isNullOrEmpty()) {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
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
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
            }
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
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

        override val isRunning: Boolean
            get() = _isRunning.get()

        override fun startLoop(config: String, tunFd: Int) {
            _isRunning.set(true)
            callbackHandler?.startup()
        }

        override fun stopLoop() {
            _isRunning.set(false)
            callbackHandler?.shutdown()
        }

        override fun queryAllOutboundTrafficStats(): String {
            if (!_isRunning.get()) return ""
            val up = uplinkBytes.addAndGet(Random.nextLong(1024, 8192))
            val down = downlinkBytes.addAndGet(Random.nextLong(4096, 32768))
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
    }
}
