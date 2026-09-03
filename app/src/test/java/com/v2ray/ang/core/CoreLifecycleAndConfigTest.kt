package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.AppPresetConfig
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class CoreLifecycleAndConfigTest {

    @Test
    fun testVlessPresetGeneratesValidConfig() {
        val profileItem = ProfileItem.create(EConfigType.VLESS).apply {
            server = "129.159.223.216"
            serverPort = "443"
            password = "c13199e0-212b-4939-8e35-dda1b75782ab"
            method = "none"
            security = "reality"
            sni = "youtubei.googleapis.com"
            publicKey = "VlatVtfydY0y5Q7lvwgHdtsAVsp4zO3QdQmFNx5XXX0"
            fingerPrint = "chrome"
            shortId = "9d"
            spiderX = "/"
        }

        val outbound = CoreOutboundBuilder.convert(profileItem)
        assertNotNull(outbound)
        assertEquals("vless", outbound!!.protocol)
        assertEquals("reality", outbound.streamSettings?.security)
        assertNotNull(outbound.streamSettings?.realitySettings)
        assertEquals("youtubei.googleapis.com", outbound.streamSettings?.realitySettings?.serverName)
        assertEquals("VlatVtfydY0y5Q7lvwgHdtsAVsp4zO3QdQmFNx5XXX0", outbound.streamSettings?.realitySettings?.publicKey)
    }

    @Test
    fun testCoreDaemonLifecycleAndBinding() {
        var protectedSocketCount = 0
        Libv2ray.socketProtector = {
            protectedSocketCount++
            true
        }

        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(l: Long, s: String?): Long = 0L
        }
        val controller = Libv2ray.newCoreController(handler)
        assertFalse(controller.isRunning)

        val testConfig = """
            {
                "outbounds": [
                    {
                        "protocol": "vless",
                        "tag": "proxy",
                        "settings": {
                            "vnext": [
                                {
                                    "address": "129.159.223.216",
                                    "port": 443,
                                    "users": [
                                        {
                                            "id": "c13199e0-212b-4939-8e35-dda1b75782ab",
                                            "encryption": "none",
                                            "flow": "",
                                            "level": 8
                                        }
                                    ]
                                }
                            ]
                        },
                        "streamSettings": {
                            "network": "tcp",
                            "security": "reality",
                            "realitySettings": {
                                "show": false,
                                "fingerprint": "chrome",
                                "serverName": "youtubei.googleapis.com",
                                "publicKey": "VlatVtfydY0y5Q7lvwgHdtsAVsp4zO3QdQmFNx5XXX0",
                                "shortId": "9d",
                                "spiderX": "/"
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        controller.startLoop(testConfig, 0)
        assertTrue(controller.isRunning)

        // Verify ports are bound and accepting connections
        var socksConnected = false
        try {
            Socket("127.0.0.1", 10808).use {
                socksConnected = true
            }
        } catch (_: Exception) {}
        assertTrue("SOCKS port 10808 must be listening", socksConnected)

        var httpConnected = false
        try {
            Socket("127.0.0.1", 10809).use {
                httpConnected = true
            }
        } catch (_: Exception) {}
        assertTrue("HTTP port 10809 must be listening", httpConnected)

        controller.stopLoop()
        assertFalse(controller.isRunning)
    }

    @Test
    fun testGlobalRoutingRulesHaveDirectDnsAndNoGeoDependencies() {
        val rules = AppPresetConfig.createGlobalRoutingRuleset()
        assertTrue("Ruleset must not be empty", rules.isNotEmpty())

        val dnsRule = rules.find { it.remarks == "Direct DNS" }
        assertNotNull("Direct DNS rule must exist", dnsRule)
        assertEquals("direct", dnsRule!!.outboundTag)
        assertEquals("53", dnsRule.port)

        for (rule in rules) {
            rule.ip?.forEach { ip ->
                assertFalse("IP rules must not require external geoip: $ip", ip.startsWith("geoip:"))
            }
            rule.domain?.forEach { dom ->
                assertFalse("Domain rules must not require external geosite: $dom", dom.startsWith("geosite:"))
            }
        }
    }
}
