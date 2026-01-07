package com.gorunjinian.metrovault.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A reusable segmented toggle component with Material 3 styling.
 * Used for binary or multi-option selection like Single-sig/Multisig, Public/Private, etc.
 *
 * @param options List of option labels to display
 * @param selectedIndex Currently selected option index
 * @param onSelect Callback when an option is selected
 * @param modifier Modifier for the toggle container
 * @param compact If true, uses smaller padding suitable for app bar
 */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val horizontalPadding = if (compact) 12.dp else 0.dp
    val verticalPadding = if (compact) 8.dp else 10.dp
    
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .then(if (!compact) Modifier.weight(1f) else Modifier)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = if (compact) MaterialTheme.typography.labelMedium 
                           else MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Convenience overload for two-option toggles with callback lambda for each option.
 */
@Composable
fun SegmentedToggle(
    firstOption: String,
    secondOption: String,
    isSecondSelected: Boolean,
    onSelectFirst: () -> Unit,
    onSelectSecond: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    SegmentedToggle(
        options = listOf(firstOption, secondOption),
        selectedIndex = if (isSecondSelected) 1 else 0,
        onSelect = { index -> 
            if (index == 0) onSelectFirst() else onSelectSecond() 
        },
        modifier = modifier,
        compact = compact
    )
}
