package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.extension.toSpeedString
import kotlinx.coroutines.delay
import java.util.Locale

// VIP Luxury Color Tokens
private val BgObsidian = Color(0xFF070A12)
private val BgNavy = Color(0xFF10192D)
private val SurfaceCard = Color(0xFF121A2A)
private val StrokeCard = Color(0xFF1E2C48)
private val VipGold = Color(0xFFFFB800)
private val VipGoldDark = Color(0xFF261D05)
private val NeonEmerald = Color(0xFF00E676)
private val NeonCyan = Color(0xFF00E5FF)
private val ElectricViolet = Color(0xFF8C52FF)
private val VibrantAmber = Color(0xFFFF9100)
private val CyberSlate = Color(0xFF25334D)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted = Color(0xFF64748B)

enum class VpnDashboardState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting
}

@Composable
fun MainDashboardScreen(
    isRunning: Boolean,
    status: MainStatus,
    onToggleClick: () -> Unit,
    onTestPingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Derive UI state from isRunning and status
    val dashboardState = remember(isRunning, status) {
        when {
            status is MainStatus.Testing -> VpnDashboardState.Connecting
            isRunning -> VpnDashboardState.Connected
            else -> VpnDashboardState.Disconnected
        }
    }

    // Ping value resolution
    val pingText = remember(status, isRunning) {
        when {
            !isRunning -> "-- ms"
            status is MainStatus.ConnectionTest -> {
                if (status.result.delayMillis > 0) "${status.result.delayMillis} ms"
                else if (status.result.delayMillis == 0L) "< 1 ms"
                else "-- ms"
            }
            status is MainStatus.Testing -> "..."
            else -> "-- ms"
        }
    }

    // Active session duration counter
    var sessionDurationSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                sessionDurationSeconds = elapsed
                delay(1000)
            }
        } else {
            sessionDurationSeconds = 0L
        }
    }

    val formattedDuration = remember(sessionDurationSeconds) {
        val hours = sessionDurationSeconds / 3600
        val minutes = (sessionDurationSeconds % 3600) / 60
        val seconds = sessionDurationSeconds % 60
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    // Traffic upload/download polling
    var downloadSpeedText by remember { mutableStateOf("0.0 KB/s") }
    var uploadSpeedText by remember { mutableStateOf("0.0 KB/s") }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            var lastQueryTime = System.currentTimeMillis()
            while (true) {
                delay(1500)
                try {
                    val stats = CoreServiceManager.queryAllOutboundTrafficStats()
                    var downBytes = 0L
                    var upBytes = 0L
                    stats.forEach { stat ->
                        if (stat.direction.equals("downlink", ignoreCase = true)) {
                            downBytes += stat.value
                        } else if (stat.direction.equals("uplink", ignoreCase = true)) {
                            upBytes += stat.value
                        }
                    }
                    val now = System.currentTimeMillis()
                    val seconds = ((now - lastQueryTime) / 1000.0).coerceAtLeast(0.5)
                    lastQueryTime = now
                    val downRate = (downBytes / seconds).toLong()
                    val upRate = (upBytes / seconds).toLong()
                    downloadSpeedText = if (downRate > 0) downRate.toSpeedString() else "0.0 KB/s"
                    uploadSpeedText = if (upRate > 0) upRate.toSpeedString() else "0.0 KB/s"
                } catch (_: Throwable) {
                    downloadSpeedText = "0.0 KB/s"
                    uploadSpeedText = "0.0 KB/s"
                }
            }
        } else {
            downloadSpeedText = "0.0 KB/s"
            uploadSpeedText = "0.0 KB/s"
        }
    }

    val scrollState = rememberScrollState()

    val bgGradient = Brush.verticalGradient(
        0.0f to BgNavy,
        0.35f to Color(0xFF0C1322),
        1.0f to BgObsidian
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgGradient)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Top Header / Server Location Card with Germany Flag & VIP Badge
        ServerLocationCard(
            dashboardState = dashboardState,
            pingText = pingText,
            onClick = onTestPingClick
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 2. Central One-Tap Power Control (Hyper-Polished Cyber/Neon Luxury)
        CentralPowerControl(
            dashboardState = dashboardState,
            onToggleClick = onToggleClick
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 3. Bottom Metrics Grid (2x2 Glass Tiles)
        MetricsGrid(
            pingText = pingText,
            durationText = formattedDuration,
            downloadSpeed = downloadSpeedText,
            uploadSpeed = uploadSpeedText,
            onPingClick = onTestPingClick
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * 1. Server Location Card
 * Elevated glassmorphic container: Background #121A2A with 1dp glowing border (#1E2C48) and 20dp rounded corners.
 * Displays German flag with 4dp radius, bold location name, glowing emerald badge, and VIP Server badge.
 */
@Composable
private fun ServerLocationCard(
    dashboardState: VpnDashboardState,
    pingText: String,
    onClick: () -> Unit
) {
    val isConnected = dashboardState == VpnDashboardState.Connected
    val isConnecting = dashboardState == VpnDashboardState.Connecting

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, StrokeCard, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // German Flag Vector with 4dp rounded corners
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_flag_germany),
                    contentDescription = stringResource(R.string.server_location_default),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Server Location & Quality Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.server_location_default),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 0.3.sp
                    ),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Ping & Quality Indicator Badge (Glowing Emerald Badge)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonEmerald.copy(alpha = 0.12f))
                        .border(1.dp, NeonEmerald.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isConnected -> NeonEmerald
                                        isConnecting -> VipGold
                                        else -> NeonEmerald
                                    }
                                )
                        )

                        Text(
                            text = when {
                                isConnected && pingText != "-- ms" && pingText != "..." -> "$pingText • " + stringResource(R.string.server_quality_fast)
                                else -> stringResource(R.string.server_quality_fast)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = NeonEmerald
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // VIP Server Gold Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(VipGoldDark)
                    .border(1.dp, VipGold.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.vip_server_badge),
                    color = VipGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * 2. Central One-Tap Power Control
 * Luxury Cyber/Neon power button (140dp) with multi-layered depth, state-based pulsing halos, and animated status dot.
 */
@Composable
private fun CentralPowerControl(
    dashboardState: VpnDashboardState,
    onToggleClick: () -> Unit
) {
    val isConnected = dashboardState == VpnDashboardState.Connected
    val isConnecting = dashboardState == VpnDashboardState.Connecting

    val primaryGlowColor by animateColorAsState(
        targetValue = when (dashboardState) {
            VpnDashboardState.Connected -> NeonEmerald
            VpnDashboardState.Connecting -> VipGold
            else -> CyberSlate
        },
        animationSpec = tween(durationMillis = 450),
        label = "PrimaryGlowColor"
    )

    // Infinite breathing animations for dual rings and status dot
    val infiniteTransition = rememberInfiniteTransition(label = "HaloRingsTransition")

    // Outer Ring 1
    val halo1Scale by infiniteTransition.animateFloat(
        initialValue = 1.02f,
        targetValue = if (isConnected || isConnecting) 1.22f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Halo1Scale"
    )
    val halo1Alpha by infiniteTransition.animateFloat(
        initialValue = if (isConnected || isConnecting) 0.38f else 0.10f,
        targetValue = if (isConnected || isConnecting) 0.05f else 0.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Halo1Alpha"
    )

    // Outer Ring 2 (Offset Phase)
    val halo2Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected || isConnecting) 1.38f else 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Halo2Scale"
    )
    val halo2Alpha by infiniteTransition.animateFloat(
        initialValue = if (isConnected || isConnecting) 0.25f else 0.06f,
        targetValue = if (isConnected || isConnecting) 0.01f else 0.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Halo2Alpha"
    )

    // Connecting spin angle
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ConnectingSpin"
    )

    // Status indicator pulse dot
    val pulseDotScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseDotScale"
    )
    val pulseDotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseDotAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer Halo Ring 2 (Largest)
            Box(
                modifier = Modifier
                    .size(195.dp)
                    .scale(halo2Scale)
                    .clip(CircleShape)
                    .background(primaryGlowColor.copy(alpha = halo2Alpha))
            )

            // Outer Halo Ring 1 (Mid)
            Box(
                modifier = Modifier
                    .size(168.dp)
                    .scale(halo1Scale)
                    .clip(CircleShape)
                    .background(primaryGlowColor.copy(alpha = halo1Alpha))
            )

            // Inner Decorative Ring with rotating/gradient sweep
            Box(
                modifier = Modifier
                    .size(152.dp)
                    .then(if (isConnecting) Modifier.rotate(spinAngle) else Modifier)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        brush = when {
                            isConnected -> Brush.sweepGradient(listOf(NeonEmerald, NeonCyan, NeonEmerald))
                            isConnecting -> Brush.sweepGradient(listOf(VipGold, Color(0xFFFFE57F), VipGold))
                            else -> Brush.sweepGradient(listOf(CyberSlate, Color(0xFF3B4F73), CyberSlate))
                        },
                        shape = CircleShape
                    )
            )

            // Central One-Tap Button (140dp x 140dp)
            Surface(
                onClick = onToggleClick,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = when {
                            isConnected -> NeonEmerald.copy(alpha = 0.85f)
                            isConnecting -> VipGold.copy(alpha = 0.85f)
                            else -> Color(0xFF2E3E5B)
                        },
                        shape = CircleShape
                    ),
                shape = CircleShape,
                color = Color.Transparent,
                shadowElevation = 14.dp
            ) {
                // Button Interior Dark Metallic Disc with Depth
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = when {
                                isConnected -> Brush.radialGradient(
                                    listOf(
                                        Color(0xFF00FF88),
                                        Color(0xFF00C853),
                                        Color(0xFF006428)
                                    )
                                )
                                isConnecting -> Brush.radialGradient(
                                    listOf(
                                        Color(0xFFFFE082),
                                        Color(0xFFFFB800),
                                        Color(0xFFB27B00)
                                    )
                                )
                                else -> Brush.radialGradient(
                                    listOf(
                                        Color(0xFF25334D),
                                        Color(0xFF162134),
                                        Color(0xFF0C121E)
                                    )
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_power_48dp),
                        contentDescription = stringResource(
                            if (isConnected) R.string.btn_disconnect else R.string.btn_connect
                        ),
                        tint = when {
                            isConnected -> Color.White
                            isConnecting -> Color.White
                            else -> Color(0xFF7E93B3)
                        },
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Connection State Typography with Animated Pulsating Indicator Dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(if (isConnected || isConnecting) pulseDotScale else 1.0f)
                    .clip(CircleShape)
                    .background(
                        when (dashboardState) {
                            VpnDashboardState.Connected -> NeonEmerald.copy(alpha = pulseDotAlpha)
                            VpnDashboardState.Connecting -> VipGold.copy(alpha = pulseDotAlpha)
                            else -> TextMuted
                        }
                    )
            )

            Text(
                text = when (dashboardState) {
                    VpnDashboardState.Connected -> stringResource(R.string.vpn_status_connected)
                    VpnDashboardState.Connecting -> stringResource(R.string.vpn_status_connecting)
                    VpnDashboardState.Disconnecting -> stringResource(R.string.vpn_status_stopping)
                    VpnDashboardState.Disconnected -> stringResource(R.string.vpn_status_disconnected)
                },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.4.sp
                ),
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Secondary Action Subtext
        Text(
            text = stringResource(if (isConnected) R.string.btn_disconnect else R.string.btn_connect),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                fontSize = 14.sp
            ),
            color = when {
                isConnected -> NeonEmerald
                isConnecting -> VipGold
                else -> Color(0xFF64748B)
            }
        )
    }
}

/**
 * 3. Bottom Metrics Grid
 * 2x2 Glassmorphic Cards with tinted icon containers and high-contrast digital monospace metrics.
 */
@Composable
private fun MetricsGrid(
    pingText: String,
    durationText: String,
    downloadSpeed: String,
    uploadSpeed: String,
    onPingClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Row 1: Ping (Neon Cyan #00E5FF) & Duration (Electric Violet #8C52FF)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.stat_ping),
                value = pingText,
                icon = painterResource(R.drawable.ic_network_ping_24dp),
                accentColor = NeonCyan,
                onClick = onPingClick,
                modifier = Modifier.weight(1f)
            )

            MetricCard(
                title = stringResource(R.string.stat_duration),
                value = durationText,
                icon = painterResource(R.drawable.ic_timer_24dp),
                accentColor = ElectricViolet,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Download (Neon Emerald #00E676) & Upload (Vibrant Gold/Orange #FF9100)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.stat_download),
                value = downloadSpeed,
                icon = painterResource(R.drawable.ic_arrow_download_24dp),
                accentColor = NeonEmerald,
                modifier = Modifier.weight(1f)
            )

            MetricCard(
                title = stringResource(R.string.stat_upload),
                value = uploadSpeed,
                icon = painterResource(R.drawable.ic_arrow_upload_24dp),
                accentColor = VibrantAmber,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Individual Glassmorphic Metric Card
 */
@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: Painter,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val cardShape = RoundedCornerShape(20.dp)
    val clickableModifier = if (onClick != null) {
        modifier
            .clip(cardShape)
            .border(1.dp, StrokeCard, cardShape)
            .clickable(onClick = onClick)
    } else {
        modifier
            .clip(cardShape)
            .border(1.dp, StrokeCard, cardShape)
    }

    Card(
        modifier = clickableModifier,
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Tinted Neon Icon Container
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.12f))
                        .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Large, bold monospace digital numbers for metrics
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 19.sp,
                    letterSpacing = 0.5.sp
                ),
                color = Color.White
            )
        }
    }
}
