package com.gorunjinian.metrovault.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R

/** Height of [HomeActionPill] at the default font size. */
internal val HomeActionPillHeight = 64.dp

/**
 * Home's two actions in one floating pill: add a wallet, and scan a QR code. Each half is its own
 * button with a label under its icon, so the scanner icon isn't mistaken for "show a QR code".
 * A null [onScan] shows Scan disabled.
 */
@Composable
internal fun HomeActionPill(
    onAdd: () -> Unit,
    onScan: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillAction(icon = R.drawable.ic_add, label = "Add", onClick = onAdd)
            VerticalDivider(
                modifier = Modifier.height(32.dp),
                color = LocalContentColor.current.copy(alpha = 0.2f)
            )
            PillAction(icon = R.drawable.ic_qr_code_scanner, label = "Scan", onClick = onScan)
        }
    }
}

/** One half of the pill. The label names the button, so the icon has no description of its own. */
@Composable
private fun PillAction(@DrawableRes icon: Int, label: String, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    val color = LocalContentColor.current.let { if (enabled) it else it.copy(alpha = 0.38f) }
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button) { onClick?.invoke() }
            .heightIn(min = HomeActionPillHeight)
            .widthIn(min = 88.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
