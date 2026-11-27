package com.gorunjinian.metrovault.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A pill-shaped floating action button with an icon and a label, which shrinks to a round button
 * with the icon alone while [expanded] is false. A null [onClick] shows it disabled.
 *
 * Pair it with [rememberFabExpanded] to shrink it while the page scrolls down.
 */
@Composable
fun CollapsibleFab(
    icon: Painter,
    text: String,
    expanded: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val enabled = onClick != null
    Surface(
        onClick = { onClick?.invoke() },
        enabled = enabled,
        // The button carries the label so a screen reader still names it while collapsed
        modifier = modifier.semantics {
            contentDescription = text
            role = Role.Button
        },
        shape = CircleShape,
        // Home's action pill colors; disabled dims only the content, as the pill's Scan does
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer.let { if (enabled) it else it.copy(alpha = 0.38f) },
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
            ) {
                Text(
                    text = text,
                    // Named by the button already; read twice otherwise
                    modifier = Modifier
                        .padding(start = 12.dp, end = 4.dp)
                        .clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Whether a [CollapsibleFab] over a page scrolled by [scrollState] shows its label: at first and
 * while the page scrolls back up, but not while it scrolls down.
 *
 * Read the returned state inside the button's slot, so only the button recomposes when it changes.
 */
@Composable
fun rememberFabExpanded(scrollState: ScrollState): State<Boolean> {
    val expanded = remember(scrollState) { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        var previous = scrollState.value
        snapshotFlow { scrollState.value }.collect { current ->
            if (current != previous) expanded.value = current < previous
            previous = current
        }
    }
    return expanded
}
