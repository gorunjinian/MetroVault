package com.gorunjinian.metrovault.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Developer avatar on the About screen: a white script "g" on a solid blue square.
 *
 * Traced from the former 256x256 `account_profile.webp` so the bitmap no longer ships in the
 * APK. The outline is SVG path data rather than `curveTo` calls because the string compresses
 * far better inside the APK than the equivalent float-loading bytecode.
 */
internal val DeveloperAvatar: ImageVector by lazy {
    ImageVector.Builder(
        name = "DeveloperAvatar",
        defaultWidth = 256.dp,
        defaultHeight = 256.dp,
        viewportWidth = 256f,
        viewportHeight = 256f
    )
        .addPath(addPathNodes("M0 0h256v256H0z"), fill = SolidColor(Color(0xFF00A4E7)))
        // The outer outline followed by the two counters, which even-odd filling leaves open
        .addPath(
            addPathNodes(G_GLYPH_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.White)
        )
        .build()
}

private const val G_GLYPH_PATH =
    "M93.5 217.1c-10.8 -1.8 -17 -5.5 -24.5 -14.6c-0.6 -0.8 -2.7 -4.1 -2.7 -4.4" +
    "c0 -0.1 -0.2 -0.7 -0.5 -1.4c-4.2 -9.4 -4.1 -22 0.4 -32.5c2.7 -6.4 8.9 -13.2 16.3 -18" +
    "c4 -2.6 9.9 -5.6 12.4 -6.2c0.7 -0.1 2.2 -0.5 3.3 -0.8c8 -2.3 16.3 -3.2 29.4 -3.2" +
    "c11.1 0 10.6 0.1 11.6 -2.3c0.3 -0.7 0.7 -1.7 0.8 -2.2c0.1 -0.5 0.4 -1.3 0.5 -1.6" +
    "c0.3 -0.7 0.5 -0.8 -5.1 0.4c-10.3 2.1 -19.7 -0.2 -26.3 -6.5" +
    "c-4.9 -4.7 -9.1 -14.4 -9.1 -21.5c0 -3.7 0.7 -3.4 -9.2 -3.7c-4.8 -0.1 -9 -0.3 -9.5 -0.4" +
    "c-6.4 -2.1 -5.8 -12.7 1 -17c3.1 -1.8 5.2 -2.2 13.6 -2.2c5.1 0 7.5 -0.1 8.1 -0.4" +
    "c0.7 -0.4 2.4 -3.1 3.8 -5.9c2 -4.4 11.1 -15.2 16 -19.2c0.9 -0.7 1.9 -1.6 2.2 -1.9" +
    "c0.3 -0.3 2 -1.5 3.8 -2.7c20.2 -13.4 44.1 -10.6 52.5 6.1c1.9 3.8 2.6 7.1 2.7 12.7" +
    "c0.1 17 -13.1 28.9 -33.8 30.6c-2.6 0.2 -10.1 0.4 -16.6 0.4c-14 0 -13.1 -0.3 -12.9 3.3" +
    "c0.4 6.2 4.3 9 12.2 9c5.3 0 7.6 -0.8 15.4 -5.3c6.7 -3.8 11.3 -4.6 15.4 -2.5" +
    "c3.9 1.9 5.3 4.2 5.3 8.7c0 3.2 0 3.2 -3 10.7c-0.6 1.3 -1.5 3.5 -2 4.9" +
    "c-0.5 1.4 -1.5 3.6 -2 5c-0.6 1.3 -1 2.6 -0.9 2.8c0.1 0.1 3.7 0.4 8 0.5" +
    "c8.3 0.2 9.7 0.4 12.3 2.1c3.9 2.5 3.9 9.9 -0.1 13.8c-3.4 3.3 -6.4 3.9 -18.2 4" +
    "c-10.7 0 -10.7 0 -11.5 2.2c-0.4 0.9 -1.1 2.6 -1.6 3.9c-0.5 1.3 -1.4 3.5 -1.9 4.9" +
    "c-1.7 4 -3.2 7.7 -3.9 9.5c-0.4 0.9 -1.1 2.5 -1.6 3.5c-0.8 1.9 -4 8.3 -5.9 11.8" +
    "c-7.6 14 -17.3 22.2 -29.4 24.9c-3.1 0.7 -12.3 1.1 -14.8 0.7z" +
    "M105.8 194.9c6.2 -3.1 10.6 -8.2 15.7 -18.6c1.4 -2.7 2.5 -5.1 2.5 -5.3" +
    "c0 -0.2 0.4 -1.3 1 -2.4c0.5 -1.2 1.4 -3.3 2 -4.8c0.6 -1.4 1.5 -3.7 2 -4.9" +
    "c0.6 -1.3 0.9 -2.4 0.8 -2.6c-0.3 -0.5 -11 -0.3 -14.6 0.2c-12.5 1.9 -18.9 4.7 -24.9 10.8" +
    "c-9 9.4 -6.9 23.5 4.2 28.1c2.4 1 8.8 0.7 11.3 -0.5z" +
    "M151.1 78.5c9.3 -1.5 13 -4.4 13.1 -10.5c0 -2.8 -0.1 -2.9 -1.2 -4.4" +
    "c-4 -4.8 -11.5 -4.7 -19.5 0.1c-5.5 3.3 -15.2 12.7 -15.2 14.6c0 0.8 17.9 0.9 22.8 0.2z"
