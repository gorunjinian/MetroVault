package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage

/**
 * The app's warning before a risky action: deleting wallets or keys, revealing recovery
 * material, turning on a destructive security setting, or discarding entered data.
 * Red warning icon, a question as the title, the explanation, Cancel, and a confirm
 * button that names the action.
 *
 * @param confirmLabel the action itself ("Delete", "Show Seed Phrase"), not "OK" or "Yes".
 * @param destructive colors the confirm label red; use it when confirming deletes, wipes
 *   or discards something. Leave it off for reveals, whose risk is who sees the screen.
 * @param content the explanation, stacked with 8dp spacing. Use the `message` overload
 *   when it is a single paragraph.
 */
@Composable
fun WarningDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(title) },
        text = {
            // Scrolls when a long list (e.g. the wallets a key deletion affects) outgrows the dialog
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * A [WarningDialog] followed by [VerifyPasswordDialog]: the gate in front of actions that
 * delete data or reveal secrets. It tracks which of the two steps is showing; the caller only
 * decides whether the gate is on screen, and removing it resets it to the first step.
 *
 * Confirming the warning moves on to the password; dismissing either step calls [onDismiss].
 * [onVerified], [isLoading] and [errorMessage] work as in [VerifyPasswordDialog], so the
 * password step stays up after verification until the caller removes the gate.
 *
 * @param skipWarning opens straight on the password step (e.g. deletes from an edit mode,
 *   where the user already chose the action); read only when the gate first appears.
 */
@Composable
fun PasswordGatedWarningDialog(
    title: String,
    confirmLabel: String,
    secureStorage: SecureStorage,
    isDecoyMode: Boolean,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    destructive: Boolean = true,
    skipWarning: Boolean = false,
    isLoading: Boolean = false,
    errorMessage: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    var warningAccepted by remember { mutableStateOf(skipWarning) }

    if (!warningAccepted) {
        WarningDialog(
            title = title,
            confirmLabel = confirmLabel,
            onConfirm = { warningAccepted = true },
            onDismiss = onDismiss,
            destructive = destructive,
            content = content
        )
    } else {
        VerifyPasswordDialog(
            secureStorage = secureStorage,
            isDecoyMode = isDecoyMode,
            onDismiss = onDismiss,
            onVerified = onVerified,
            isLoading = isLoading,
            errorMessage = errorMessage
        )
    }
}

/** [WarningDialog] whose explanation is a single paragraph. */
@Composable
fun WarningDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true
) {
    WarningDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = destructive
    ) {
        Text(message)
    }
}
