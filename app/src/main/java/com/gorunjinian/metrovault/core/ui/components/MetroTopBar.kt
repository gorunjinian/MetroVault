package com.gorunjinian.metrovault.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.gorunjinian.metrovault.R

/**
 * The app-wide screen header: a title, a back arrow, and optional trailing actions.
 *
 * Every screen in MetroVault is reached from somewhere else and has a back affordance,
 * so [onBack] is required rather than a nullable slot.
 *
 * The bar is transparent by default so it blends into the [androidx.compose.material3.Scaffold]
 * background. The few screens that want the opaque Material surface pass
 * `colors = TopAppBarDefaults.topAppBarColors()` explicitly.
 *
 * @param backContentDescription accessibility label for the navigation icon; override when
 *   the arrow means something more specific than "go back" (e.g. "Cancel").
 */

@Composable
fun MetroTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String = "Back",
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent
    ),
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = backContentDescription
                )
            }
        },
        actions = actions,
        colors = colors
    )
}
