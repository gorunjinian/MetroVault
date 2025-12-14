package com.gorunjinian.metrovault.core.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorunjinian.metrovault.lib.bitcoin.BIP39Wordlist

/**
 * A secure in-app QWERTY keyboard for mnemonic seed entry.
 * This keyboard prevents potential keylogging by third-party keyboards.
 * 
 * Features:
 * - Full QWERTY layout optimized for BIP39 word entry
 * - Real-time word completion suggestions from BIP39 wordlist
 * - Haptic feedback on key press
 * - Material3 styling matching app theme
 *
 * @param currentWord The current word being typed (for suggestions)
 * @param onKeyPress Callback when a letter key is pressed
 * @param onBackspace Callback when backspace is pressed
 * @param onWordSelected Callback when a suggestion is selected
 * @param modifier Modifier for the keyboard container
 */
@Composable
fun SecureMnemonicKeyboard(
    currentWord: String,
    onKeyPress: (Char) -> Unit,
    onBackspace: () -> Unit,
    onWordSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    
    // Get word suggestions based on current input
    val suggestions by remember(currentWord) {
        derivedStateOf {
            if (currentWord.isNotEmpty()) {
                BIP39Wordlist.getWordsWithPrefix(context, currentWord).take(8)
            } else {
                emptyList()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(bottom = 8.dp)
    ) {
        // Suggestions bar
        if (suggestions.isNotEmpty()) {
            SuggestionsBar(
                suggestions = suggestions,
                onSuggestionSelected = onWordSelected
            )
        } else if (currentWord.isNotEmpty()) {
            // Show "no matches" indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No matching words",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        } else {
            Spacer(modifier = Modifier.height(40.dp))
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            thickness = 1.dp
        )

        Spacer(modifier = Modifier.height(4.dp))

        // QWERTY keyboard layout
        val keyboardRows = listOf(
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm"
        )

        // Maximum number of keys in a row (first row has 10 letters)
        val maxKeysPerRow = 10

        keyboardRows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // Calculate how many "slots" this row should use
                // Row 0: 10 keys = 10 slots
                // Row 1: 9 keys + side padding = effectively 10 slots
                // Row 2: 7 keys + backspace (1.5x width) = effectively 8.5 slots, pad to 10
                
                when (rowIndex) {
                    0 -> {
                        // First row: all 10 keys, evenly distributed
                        row.forEach { char ->
                            KeyButton(
                                text = char.uppercase(),
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKeyPress(char)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    1 -> {
                        // Middle row: 9 keys with half-key padding on each side
                        Spacer(modifier = Modifier.weight(0.5f))
                        row.forEach { char ->
                            KeyButton(
                                text = char.uppercase(),
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKeyPress(char)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.weight(0.5f))
                    }
                    2 -> {
                        // Bottom row: 7 keys + backspace (1.5x width)
                        // Use 1.25 key width padding on left to center
                        Spacer(modifier = Modifier.weight(1.25f))
                        row.forEach { char ->
                            KeyButton(
                                text = char.uppercase(),
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKeyPress(char)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.weight(0.25f))
                        BackspaceButton(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onBackspace()
                            },
                            modifier = Modifier.weight(1.5f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Space bar row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            SpaceBarButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    // If there's a valid word, select the first suggestion
                    if (suggestions.isNotEmpty()) {
                        onWordSelected(suggestions.first())
                    }
                },
                enabled = suggestions.isNotEmpty()
            )
        }
    }
}

@Composable
private fun SuggestionsBar(
    suggestions: List<String>,
    onSuggestionSelected: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { word ->
            SuggestionChip(
                word = word,
                onClick = { onSuggestionSelected(word) }
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    word: String,
    onClick: () -> Unit
) {
    val view = LocalView.current
    
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = word,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val backgroundColor = if (isPressed) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BackspaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val backgroundColor = if (isPressed) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(com.gorunjinian.metrovault.R.drawable.ic_backspace),
            contentDescription = "Backspace",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SpaceBarButton(
    onClick: () -> Unit,
    enabled: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        isPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (enabled) "space (add word)" else "type a valid word",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            }
        )
    }
}
