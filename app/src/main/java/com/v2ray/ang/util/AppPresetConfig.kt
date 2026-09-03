package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.handler.SettingsManager

object AppPresetConfig {
    const val DEFAULT_VLESS_URI = "vless://c13199e0-212b-4939-8e35-dda1b75782ab@129.159.223.216:443?type=tcp&encryption=none&security=reality&pbk=VlatVtfydY0y5Q7lvwgHdtsAVsp4zO3QdQmFNx5XXX0&fp=chrome&sni=youtubei.googleapis.com&sid=9d&spx=%2F#mgzo4a39"
    const val DEFAULT_SUB_URL = "https://129.159.223.216:2096/sub/e1fga7bz42wn430d"

    /**
     * Initializes default routing rules to route ALL traffic through the VPN proxy tunnel (Proxy All / Global Mode)
     * by default, ensuring all external websites and apps pass through the Frankfurt server.
     * Disables complex domain-sniffing bypasses and Chinese direct-routing lists.
     */
    fun ensureGlobalRoutingDefaults(context: Context) {
        // Disable domain sniffing bypasses that interfere with global tunneling
        MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)

        // Set routing domain strategy to AsIs for global tunneling
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "AsIs")

        // Ensure default routing ruleset is clean Global (Proxy All) mode
        val existingRules = MmkvManager.decodeRoutingRulesets()
        val hasOutdatedRules = existingRules?.any { rule ->
            rule.domain?.any { it.contains("cn", ignoreCase = true) || it.startsWith("geosite:") } == true ||
            rule.ip?.any { it.contains("cn", ignoreCase = true) || it.startsWith("geoip:") } == true
        } ?: true

        if (existingRules.isNullOrEmpty() || hasOutdatedRules) {
            val globalRules = createGlobalRoutingRuleset()
            MmkvManager.encodeRoutingRulesets(globalRules)
        }
    }

    fun createGlobalRoutingRuleset(): MutableList<RulesetItem> {
        return mutableListOf(
            RulesetItem(
                remarks = "Block QUIC (UDP 443)",
                outboundTag = AppConfig.TAG_BLOCKED,
                port = "443",
                network = "udp",
                enabled = true,
                locked = false
            ),
            RulesetItem(
                remarks = "Direct DNS",
                outboundTag = AppConfig.TAG_DIRECT,
                port = "53",
                network = "udp,tcp",
                enabled = true,
                locked = false
            ),
            RulesetItem(
                remarks = "Bypass Private LAN IP",
                outboundTag = AppConfig.TAG_DIRECT,
                ip = arrayListOf(
                    "10.0.0.0/8",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "127.0.0.0/8",
                    "fc00::/7",
                    "fe80::/10",
                    "::1/128"
                ),
                enabled = true,
                locked = false
            ),
            RulesetItem(
                remarks = "Bypass Private LAN Domain",
                outboundTag = AppConfig.TAG_DIRECT,
                domain = arrayListOf(
                    "domain:local",
                    "domain:localhost"
                ),
                enabled = true,
                locked = false
            ),
            RulesetItem(
                remarks = "Proxy All Traffic",
                outboundTag = AppConfig.TAG_PROXY,
                port = "0-65535",
                enabled = true,
                locked = false
            )
        )
    }
}

typealias MmkvManager = com.v2ray.ang.handler.MmkvManager
typealias AngConfigManager = com.v2ray.ang.handler.AngConfigManager
typealias V2rayVpnService = com.v2ray.ang.service.CoreVpnService

