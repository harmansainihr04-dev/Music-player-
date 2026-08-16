package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.EqPreset
import com.example.ui.theme.DarkBlueCardBorder
import com.example.ui.theme.DarkBlueSurface
import com.example.ui.theme.DarkBlueSurfaceVariant
import com.example.ui.theme.DarkRedAccent
import com.example.ui.theme.DarkRedPrimary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun EqualizerScreen(
    eqEnabled: Boolean,
    bassBoostLevel: Float,
    bandGains: FloatArray,
    selectedPreset: EqPreset,
    onEqEnabledToggle: (Boolean) -> Unit,
    onBassBoostChange: (Float) -> Unit,
    onBandGainChange: (Int, Float) -> Unit,
    onPresetSelect: (EqPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val bandLabels = listOf("60Hz\nBass", "230Hz\nLow", "910Hz\nMid", "3.6kHz\nHigh", "14kHz\nTreble")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "EQUALIZER",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = DarkRedPrimary,
                        letterSpacing = 1.5.sp
                    )
                )
                Text(
                    text = "Simple Audio DSP & Bass Boost Controls",
                    style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (eqEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (eqEnabled) DarkRedPrimary else TextMuted,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = onEqEnabledToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkRedPrimary,
                        checkedTrackColor = DarkRedPrimary.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkBlueSurfaceVariant
                    ),
                    modifier = Modifier.testTag("eq_master_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bass Boost Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DarkBlueCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = DarkBlueSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = DarkRedPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bass Boost",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                    }

                    Text(
                        text = "${(bassBoostLevel * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = DarkRedPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = bassBoostLevel,
                    onValueChange = onBassBoostChange,
                    enabled = eqEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = DarkRedPrimary,
                        activeTrackColor = DarkRedPrimary,
                        inactiveTrackColor = DarkBlueSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bass_boost_slider")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Equalizer Presets
        Text(
            text = "PRESETS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(EqPreset.entries.toTypedArray()) { preset ->
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = { onPresetSelect(preset) },
                    enabled = eqEnabled,
                    label = { Text(preset.displayName, fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DarkRedPrimary,
                        selectedLabelColor = Color.White,
                        containerColor = DarkBlueSurface,
                        labelColor = TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = eqEnabled,
                        selected = selectedPreset == preset,
                        borderColor = DarkBlueCardBorder,
                        selectedBorderColor = DarkRedPrimary
                    ),
                    modifier = Modifier.testTag("preset_${preset.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 5-Band Equalizer Sliders Header & Reset Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FREQUENCY BANDS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            )

            OutlinedButton(
                onClick = { onPresetSelect(EqPreset.FLAT) },
                enabled = eqEnabled,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DarkRedPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkRedPrimary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Sliders Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DarkBlueCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = DarkBlueSurface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (i in 0..4) {
                    val gainDb = bandGains.getOrElse(i) { 0f }
                    val label = bandLabels[i]

                    Column(
                        modifier = Modifier.width(60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${if (gainDb > 0) "+" else ""}${gainDb.toInt()}dB",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (gainDb != 0f) DarkRedPrimary else TextMuted,
                                fontSize = 11.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        VerticalEqSlider(
                            value = gainDb,
                            onValueChange = { newGain -> onBandGainChange(i, newGain) },
                            valueRange = -12f..12f,
                            enabled = eqEnabled,
                            modifier = Modifier.testTag("eq_band_slider_$i")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
fun VerticalEqSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -12f..12f,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val trackHeight = 170.dp
    val handleHeight = 22.dp
    val padding = 11.dp // handleHeight / 2

    Box(
        modifier = modifier
            .width(48.dp)
            .height(trackHeight + handleHeight)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val totalTrackPx = trackHeight.toPx()
                    if (totalTrackPx > 0) {
                        val touchYOnTrack = (change.position.y - padding.toPx()).coerceIn(0f, totalTrackPx)
                        val fraction = (1f - (touchYOnTrack / totalTrackPx)).coerceIn(0f, 1f)
                        val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val totalTrackPx = trackHeight.toPx()
                    if (totalTrackPx > 0) {
                        val touchYOnTrack = (offset.y - padding.toPx()).coerceIn(0f, totalTrackPx)
                        val fraction = (1f - (touchYOnTrack / totalTrackPx)).coerceIn(0f, 1f)
                        val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)
                    }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        val totalRange = valueRange.endInclusive - valueRange.start
        val fraction = if (totalRange > 0) ((value - valueRange.start) / totalRange).coerceIn(0f, 1f) else 0.5f

        // Track container
        Box(
            modifier = Modifier
                .padding(top = padding)
                .width(10.dp)
                .height(trackHeight)
                .clip(RoundedCornerShape(5.dp))
                .background(DarkBlueSurfaceVariant)
                .border(1.dp, DarkBlueCardBorder, RoundedCornerShape(5.dp))
        )

        // Center 0dB indicator line
        Box(
            modifier = Modifier
                .offset(y = padding + (trackHeight / 2) - 1.dp)
                .width(22.dp)
                .height(2.dp)
                .background(if (enabled) TextMuted else TextMuted.copy(alpha = 0.3f))
        )

        // Active Gain Fill
        if (fraction >= 0.5f) {
            val fillFraction = fraction - 0.5f
            val fillHeight = trackHeight * fillFraction
            Box(
                modifier = Modifier
                    .offset(y = padding + (trackHeight * 0.5f) - fillHeight)
                    .width(10.dp)
                    .height(fillHeight)
                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                    .background(if (enabled) DarkRedPrimary else TextMuted)
            )
        } else {
            val fillFraction = 0.5f - fraction
            val fillHeight = trackHeight * fillFraction
            Box(
                modifier = Modifier
                    .offset(y = padding + (trackHeight * 0.5f))
                    .width(10.dp)
                    .height(fillHeight)
                    .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                    .background(if (enabled) DarkRedPrimary.copy(alpha = 0.6f) else TextMuted.copy(alpha = 0.4f))
            )
        }

        // Fader / Thumb Handle
        val handleTop = trackHeight * (1f - fraction)
        Box(
            modifier = Modifier
                .offset(y = handleTop)
                .width(38.dp)
                .height(handleHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (enabled) DarkRedPrimary else DarkBlueSurfaceVariant
                )
                .border(
                    1.dp,
                    if (enabled) Color.White.copy(alpha = 0.4f) else DarkBlueCardBorder,
                    RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .background(if (enabled) Color.White else TextMuted)
            )
        }
    }
}

