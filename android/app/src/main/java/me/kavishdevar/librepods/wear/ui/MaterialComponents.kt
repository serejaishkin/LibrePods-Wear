package me.kavishdevar.librepods.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton

/**
 * Material You-style card component with dark theme
 */
@Composable
fun MaterialCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cardModifier = if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    } else {
        modifier.clip(RoundedCornerShape(16.dp))
    }

    Box(
        modifier = cardModifier
            .background(
                if (enabled) LibrePodsColors.Neutral800_90 else LibrePodsColors.Neutral900_40
            )
            .border(
                width = 1.dp,
                color = if (enabled) LibrePodsColors.Neutral700 else LibrePodsColors.Neutral800,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
    ) {
        content()
    }
}

/**
 * Material You-style primary button with blue accent
 */
@Composable
fun MaterialPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                spotColor = LibrePodsColors.Blue600_30,
                ambientColor = LibrePodsColors.Blue600_30
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) LibrePodsColors.Blue600 else LibrePodsColors.Neutral800,
            contentColor = Color.White
        ),
        enabled = enabled
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Material You-style danger button for destructive actions
 */
@Composable
fun MaterialDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                spotColor = LibrePodsColors.Red500_20,
                ambientColor = LibrePodsColors.Red500_20
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) LibrePodsColors.Red600 else LibrePodsColors.Neutral800,
            contentColor = Color.White
        ),
        enabled = enabled
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Material You-style secondary button for less important actions
 */
@Composable
fun MaterialSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = LibrePodsColors.Neutral800,
            contentColor = LibrePodsColors.Neutral300
        ),
        enabled = enabled
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Material You-style compact button for tiles and small actions
 */
@Composable
fun MaterialCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = LibrePodsColors.Blue600,
    textColor: Color = Color.White
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = textColor
        )
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Material You-style toggle switch
 */
@Composable
fun MaterialToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(48.dp)
            .height(26.dp)
            .background(
                if (checked) LibrePodsColors.Blue600 else LibrePodsColors.Neutral800,
                CircleShape
            )
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color.White, CircleShape)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
        )
    }
}

/**
 * Material You-style status badge
 */
@Composable
fun MaterialStatusBadge(
    text: String,
    color: Color = LibrePodsColors.Blue400,
    backgroundColor: Color = LibrePodsColors.Blue500_15
) {
    Box(
        modifier = Modifier
            .background(
                backgroundColor,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Material You-style section header
 */
@Composable
fun MaterialSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = LibrePodsColors.Neutral200,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * Material You-style info row with label and value
 */
@Composable
fun MaterialInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = LibrePodsColors.Neutral500,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = LibrePodsColors.Neutral300,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Material You-style battery indicator
 */
@Composable
fun MaterialBatteryIndicator(
    label: String,
    level: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier
) {
    val value = if (level != null && level in 0..100) "$level%" else "--"
    val color = when {
        level == null -> LibrePodsColors.Neutral500
        level <= 20 -> LibrePodsColors.Red600
        level <= 50 -> LibrePodsColors.Amber500
        else -> LibrePodsColors.Emerald500
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = LibrePodsColors.Neutral400,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (charging && value != "--") "$value +" else value,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}