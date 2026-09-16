package com.appslabs.mic.ui

import android.media.AudioDeviceInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslabs.mic.audio.AudioDevice
import com.appslabs.mic.audio.AudioRoute
import com.appslabs.mic.audio.DevicePriorityManager
import com.appslabs.mic.audio.PlaybackResult

// ── Design tokens ─────────────────────────────────────────────────────────────

private val BgDeep        = Color(0xFF0A0E1A)
private val BgCard        = Color(0xFF131929)
private val BgCardAlt     = Color(0xFF1A2035)
private val AccentBlue    = Color(0xFF4D9FFF)
private val AccentTeal    = Color(0xFF00D4B4)
private val AccentPurple  = Color(0xFF9B6EFF)
private val TextPrimary   = Color(0xFFEAEEF8)
private val TextSecondary = Color(0xFF8A94B0)
private val TextDisabled  = Color(0xFF3D4560)
private val GreenOk       = Color(0xFF2ECC71)
private val YellowWarn    = Color(0xFFF39C12)
private val RedErr        = Color(0xFFE74C3C)
private val OrangeConnected = Color(0xFFE67E22)
private val BorderSubtle  = Color(0xFF1E2A45)
private val SelectedBorder = AccentBlue.copy(alpha = 0.6f)

private val GradientHeader = Brush.linearGradient(listOf(AccentBlue, AccentPurple))

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
fun AudioRouterScreen(viewModel: MainViewModel) {
    val availableDevices by viewModel.availableDevices.collectAsState()
    val preferredRoute   by viewModel.preferredRoute.collectAsState()
    val priorityList     by viewModel.devicePriorityList.collectAsState()
    val isPlaying        by viewModel.isPlaying.collectAsState()
    val lastResult       by viewModel.lastResult.collectAsState()

    val context = LocalContext.current
    val onUnavailableClick: (String) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.speakText(message, AudioRoute.DEFAULT)
    }

    // Per-message route selection (local UI state, not persisted)
    var messageRoute by remember { mutableStateOf(AudioRoute.DEFAULT) }
    var inputText    by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDeep
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App header
            AppHeader(isPlaying = isPlaying)

            // ── Section 1: Play Content ───────────────────────────────────────
            SectionCard(title = "▶  Play Content", subtitle = "Speak text or trigger audio playback") {
                PlayContentPanel(
                    inputText    = inputText,
                    onTextChange = { inputText = it },
                    messageRoute = messageRoute,
                    availableDevices = availableDevices,
                    onMessageRouteChange = { messageRoute = it },
                    onUnavailableClick = onUnavailableClick,
                    isPlaying    = isPlaying,
                    onSpeak      = { viewModel.speakText(inputText, messageRoute) },
                    onStop       = { viewModel.stop() },
                    onReplay     = { viewModel.replay() }
                )
            }

            // ── Section 2: Output Priority (Always visible) ───────────────────
            SectionCard(
                title = "🎯  Output Priority / Playback Device",
                subtitle = "Drag to reorder. Active device is green, connected devices are orange."
            ) {
                DevicePriorityList(
                    priorityList = priorityList,
                    availableDevices = availableDevices,
                    resolvedWinner = resolveActiveDevice(preferredRoute, messageRoute, priorityList, availableDevices),
                    onMoveUp   = { viewModel.movePriorityUp(it) },
                    onMoveDown = { viewModel.movePriorityDown(it) }
                )
            }

            // ── Section 3: Global Preferred Route ─────────────────────────────
            SectionCard(title = "🔈  Audio Output", subtitle = "Global preferred destination (Overrides priority list)") {
                GlobalRouteSelector(
                    selected = preferredRoute,
                    availableDevices = availableDevices,
                    onSelect = { viewModel.setPreferredRoute(it) },
                    onUnavailableClick = onUnavailableClick
                )
            }

            // ── Result badge ──────────────────────────────────────────────────
            lastResult?.let { ResultBadge(result = it) }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── App header ────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(isPlaying: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Audio Router",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.width(10.dp))
            PlayingDot(isPlaying = isPlaying)
        }
        Text(
            text = "Native Android Audio Playback & Routing",
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun PlayingDot(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val color by animateColorAsState(
        targetValue = if (isPlaying) GreenOk else TextDisabled,
        animationSpec = tween(400),
        label = "dot_color"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .scale(if (isPlaying) scale else 1f)
            .clip(CircleShape)
            .background(color)
    )
}

// ── Section card container ────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentBlue
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
            )
            HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

// ── Section 1: Global route selector ─────────────────────────────────────────

private data class RouteOption(
    val route: AudioRoute,
    val label: String,
    val icon: String,
    val notConnectedMsg: String? = null,
    val unavailableHint: String? = null
)

@Composable
private fun GlobalRouteSelector(
    selected: AudioRoute,
    availableDevices: List<AudioDevice>,
    onSelect: (AudioRoute) -> Unit,
    onUnavailableClick: (String) -> Unit
) {
    val hasBluetooth = availableDevices.any { it.isBluetooth && it.isConnected }
    val hasWired     = availableDevices.any { it.isWired && it.isConnected }
    val hasSpeaker   = availableDevices.any { it.isSpeaker }

    val options = listOf(
        RouteOption(AudioRoute.DEFAULT, "System Default",
            "🔀", null, "Uses the Device Priority list below"),
        RouteOption(AudioRoute.SPEAKER, "Phone Speaker",
            "🔊", if (!hasSpeaker) "Speaker Not Connected" else null, null),
        RouteOption(AudioRoute.BLUETOOTH, "Bluetooth / Headset",
            "🎧", "Bluetooth Not Connected", if (!hasBluetooth) "No Bluetooth device connected" else null),
        RouteOption(AudioRoute.WIRED_HEADSET, "Wired Headset",
            "🎵", "Wired Headphones Not Connected", if (!hasWired) "No wired device connected" else null)
    )

    Column(modifier = Modifier.selectableGroup()) {
        options.forEachIndexed { idx, option ->
            val isAvailable = when (option.route) {
                AudioRoute.BLUETOOTH    -> hasBluetooth
                AudioRoute.WIRED_HEADSET -> hasWired
                else                    -> true
            }
            val isSelected = selected == option.route

            RouteRow(
                option = option,
                isSelected = isSelected,
                isAvailable = isAvailable,
                onClick = { 
                    if (isAvailable) onSelect(option.route) 
                    else if (option.notConnectedMsg != null) onUnavailableClick(option.notConnectedMsg)
                }
            )

            if (idx < options.lastIndex) {
                HorizontalDivider(
                    color = BorderSubtle.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RouteRow(
    option: RouteOption,
    isSelected: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit
) {
    val labelColor    = if (isAvailable) TextPrimary else TextDisabled
    val subtitleColor = if (isAvailable) TextSecondary else TextDisabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSelected) Modifier.border(
                    1.dp, SelectedBorder, RoundedCornerShape(10.dp)
                ) else Modifier
            )
            .background(if (isSelected) AccentBlue.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            enabled = isAvailable,
            colors = RadioButtonDefaults.colors(
                selectedColor   = AccentBlue,
                unselectedColor = TextSecondary,
                disabledSelectedColor   = TextDisabled,
                disabledUnselectedColor = TextDisabled
            )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = option.icon, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = option.label,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
            )
            option.unavailableHint?.let { hint ->
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = if (isAvailable) AccentTeal.copy(alpha = 0.7f) else subtitleColor
                )
            }
        }
    }
}

// ── Section 2: Device priority list ──────────────────────────────────────────

@Composable
private fun DevicePriorityList(
    priorityList: List<Int>,
    availableDevices: List<AudioDevice>,
    resolvedWinner: AudioDevice?,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    // Active winner banner
    resolvedWinner?.let { winner ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GreenOk.copy(alpha = 0.12f))
                .border(1.dp, GreenOk.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✅", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Currently active output",
                        fontSize = 11.sp,
                        color = GreenOk.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = winner.name,
                        fontSize = 13.sp,
                        color = GreenOk,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Priority rows
    priorityList.forEachIndexed { index, type ->
        val label = DevicePriorityManager.DEVICE_TYPE_LABELS[type] ?: "Device"
        val icon  = DevicePriorityManager.DEVICE_TYPE_ICONS[type] ?: "🔊"
        val connectedDevice = availableDevices.firstOrNull { it.type == type && it.isConnected }
        val isConnected = connectedDevice != null
        val isWinner    = resolvedWinner?.type == type

        PriorityRow(
            rank         = index + 1,
            label        = connectedDevice?.name ?: label,
            icon         = icon,
            isConnected  = isConnected,
            isWinner     = isWinner,
            canMoveUp    = index > 0,
            canMoveDown  = index < priorityList.lastIndex,
            onMoveUp     = { onMoveUp(type) },
            onMoveDown   = { onMoveDown(type) }
        )

        if (index < priorityList.lastIndex) {
            HorizontalDivider(
                color = BorderSubtle.copy(alpha = 0.4f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Drag up/down to reprioritize. Grayed = not connected.",
        fontSize = 11.sp,
        color = TextDisabled,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun PriorityRow(
    rank: Int,
    label: String,
    icon: String,
    isConnected: Boolean,
    isWinner: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val labelColor = when {
        isWinner    -> GreenOk
        isConnected -> GreenOk
        else        -> TextDisabled
    }
    val bgColor = when {
        isWinner    -> GreenOk.copy(alpha = 0.07f)
        isConnected -> GreenOk.copy(alpha = 0.04f)
        else        -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank badge
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isWinner    -> GreenOk.copy(alpha = 0.2f)
                        isConnected -> GreenOk.copy(alpha = 0.12f)
                        else        -> BorderSubtle
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$rank",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isWinner    -> GreenOk
                    isConnected -> GreenOk
                    else        -> TextDisabled
                }
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = icon, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isWinner) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
            )
            Text(
                text = when {
                    isWinner    -> "● Playing"
                    isConnected -> "● Connected"
                    else        -> "○ Not connected"
                },
                fontSize = 10.sp,
                color = when {
                    isWinner    -> GreenOk
                    isConnected -> GreenOk
                    else        -> TextDisabled
                }
            )
        }
        // Up / Down controls
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (canMoveUp) AccentBlue.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(enabled = canMoveUp, onClick = onMoveUp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "▲",
                fontSize = 13.sp,
                color = if (canMoveUp) AccentBlue else TextDisabled,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (canMoveDown) AccentBlue.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(enabled = canMoveDown, onClick = onMoveDown),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "▼",
                fontSize = 13.sp,
                color = if (canMoveDown) AccentBlue else TextDisabled,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Section 3: Play content ───────────────────────────────────────────────────

@Composable
private fun PlayContentPanel(
    inputText: String,
    onTextChange: (String) -> Unit,
    messageRoute: AudioRoute,
    availableDevices: List<AudioDevice>,
    onMessageRouteChange: (AudioRoute) -> Unit,
    onUnavailableClick: (String) -> Unit,
    isPlaying: Boolean,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
    onReplay: () -> Unit
) {
    // Text input
    OutlinedTextField(
        value = inputText,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Enter text to speak", color = TextSecondary) },
        minLines = 2,
        maxLines = 5,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = AccentBlue,
            unfocusedBorderColor = BorderSubtle,
            focusedTextColor     = TextPrimary,
            unfocusedTextColor   = TextPrimary,
            cursorColor          = AccentBlue,
            focusedLabelColor    = AccentBlue
        )
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Per-message route override
    Text(
        text = "Play Through",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextSecondary
    )
    Spacer(modifier = Modifier.height(8.dp))
    MessageRouteChips(
        selected = messageRoute,
        availableDevices = availableDevices,
        onSelect = onMessageRouteChange,
        onUnavailableClick = onUnavailableClick
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onSpeak,
            enabled = inputText.isNotBlank() && !isPlaying,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor   = Color.White,
                disabledContainerColor = BorderSubtle,
                disabledContentColor   = TextDisabled
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (isPlaying) "Speaking…" else "▶  Speak Text",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }

        OutlinedButton(
            onClick = onStop,
            enabled = isPlaying,
            modifier = Modifier.weight(0.5f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = RedErr,
                disabledContentColor = TextDisabled
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, if (isPlaying) RedErr.copy(alpha = 0.5f) else BorderSubtle
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("⏹ Stop", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(
        onClick = onReplay,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentTeal),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, AccentTeal.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text("↩  Replay Last", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageRouteChips(
    selected: AudioRoute,
    availableDevices: List<AudioDevice>,
    onSelect: (AudioRoute) -> Unit,
    onUnavailableClick: (String) -> Unit
) {
    val hasBluetooth = availableDevices.any { it.isBluetooth && it.isConnected }
    val hasWired     = availableDevices.any { it.isWired && it.isConnected }
    val hasSpeaker   = availableDevices.any { it.isSpeaker }

    val options = listOf(
        Triple(AudioRoute.DEFAULT, "Preferred", null),
        Triple(AudioRoute.SPEAKER, "Speaker", if (!hasSpeaker) "Speaker Not Connected" else null),
        Triple(AudioRoute.BLUETOOTH, "Bluetooth", "Bluetooth Not Connected"),
        Triple(AudioRoute.WIRED_HEADSET, "Wired", "Wired Headphones Not Connected")
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (route, label, notConnectedMsg) ->
            val isSelected = selected == route
            val isAvailable = when (route) {
                AudioRoute.BLUETOOTH -> hasBluetooth
                AudioRoute.WIRED_HEADSET -> hasWired
                else -> true
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isSelected) AccentBlue.copy(alpha = 0.2f)
                        else BgCardAlt
                    )
                    .border(
                        1.dp,
                        if (isSelected) AccentBlue else BorderSubtle,
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { 
                        if (isAvailable) onSelect(route)
                        else if (notConnectedMsg != null) onUnavailableClick(notConnectedMsg)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) AccentBlue else TextSecondary
                )
            }
        }
    }
}

// ── Result badge ──────────────────────────────────────────────────────────────

@Composable
private fun ResultBadge(result: PlaybackResult) {
    val (bgColor, borderColor, icon, headline, detail) = when (result) {
        is PlaybackResult.Success -> ResultStyle(
            GreenOk.copy(alpha = 0.10f), GreenOk.copy(alpha = 0.35f),
            "✅",
            "Playing via ${result.route.label}",
            result.deviceName.takeIf { it.isNotBlank() }
        )
        is PlaybackResult.Fallback -> ResultStyle(
            YellowWarn.copy(alpha = 0.10f), YellowWarn.copy(alpha = 0.35f),
            "⚠️",
            "Fallback → ${result.actualRoute.label}",
            result.reason
        )
        is PlaybackResult.Error -> ResultStyle(
            RedErr.copy(alpha = 0.10f), RedErr.copy(alpha = 0.35f),
            "❌",
            "Playback error",
            result.message
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(text = icon, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = headline,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                detail?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private data class ResultStyle(
    val bgColor: Color,
    val borderColor: Color,
    val icon: String,
    val headline: String,
    val detail: String?
)

// ── Helpers ───────────────────────────────────────────────────────────────────

private val AudioRoute.label: String
    get() = when (this) {
        AudioRoute.DEFAULT      -> "System Default"
        AudioRoute.SPEAKER      -> "Phone Speaker"
        AudioRoute.BLUETOOTH    -> "Bluetooth"
        AudioRoute.WIRED_HEADSET -> "Wired Headset"
    }

private fun resolveActiveDevice(
    preferredRoute: AudioRoute,
    messageRoute: AudioRoute,
    priorityList: List<Int>,
    availableDevices: List<AudioDevice>
): AudioDevice? {
    val effectiveRoute = when {
        messageRoute != AudioRoute.DEFAULT -> messageRoute
        preferredRoute != AudioRoute.DEFAULT -> preferredRoute
        else -> AudioRoute.DEFAULT
    }

    when (effectiveRoute) {
        AudioRoute.SPEAKER -> {
            val speaker = availableDevices.firstOrNull { it.isSpeaker }
            if (speaker != null) return speaker
        }
        AudioRoute.BLUETOOTH -> {
            val bt = availableDevices.firstOrNull { it.isBluetooth && it.isConnected }
            if (bt != null) return bt
        }
        AudioRoute.WIRED_HEADSET -> {
            val wired = availableDevices.firstOrNull { it.isWired && it.isConnected }
            if (wired != null) return wired
        }
        AudioRoute.DEFAULT -> {
            // Handled below
        }
    }

    // Fallback to priority list
    for (type in priorityList) {
        val device = availableDevices.firstOrNull { it.type == type && it.isConnected }
        if (device != null) return device
    }
    return null
}
