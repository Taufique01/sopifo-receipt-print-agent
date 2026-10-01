package com.sopifo.printagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons missing from material-icons-core, defined here instead of pulling in the much larger
 * material-icons-extended artifact. Colour is applied by Icon's tint.
 */
object AppIcons {
    /** Material "print". */
    val Print: ImageVector by lazy {
        ImageVector.Builder("Print", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                addPathNodes(
                    "M19,8H5c-1.66,0 -3,1.34 -3,3v6h4v4h12v-4h4v-6c0,-1.66 -1.34,-3 -3,-3z" +
                        "M16,19H8v-5h8v5z" +
                        "M19,12c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z" +
                        "M18,3H6v4h12V3z",
                ),
                fill = SolidColor(Color.Black),
            )
            .build()
    }

    /** Heartbeat line, for the diagnostics tab. */
    val Pulse: ImageVector by lazy {
        ImageVector.Builder("Pulse", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                addPathNodes("M2,12h4l2.5,-6 4,12 3,-8 1.5,2h5"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
}
