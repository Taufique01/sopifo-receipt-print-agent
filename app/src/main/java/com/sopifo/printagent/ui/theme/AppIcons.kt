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

    /** Material "local_shipping", for the Shipping tab. */
    val Truck: ImageVector by lazy { filled("Truck", "M20,8h-3V4H3c-1.1,0 -2,0.9 -2,2v11h2c0,1.66 1.34,3 3,3s3,-1.34 3,-3h6c0,1.66 1.34,3 3,3s3,-1.34 3,-3h2v-5l-3,-4z" +
        "M6,18.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z" +
        "M19.5,9.5l1.96,2.5H17V9.5h2.5z" +
        "M18,18.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z") }

    /** Material "receipt_long", for the Orders tab. */
    val Receipt: ImageVector by lazy { filled("Receipt", "M19.5,3.5L18,2l-1.5,1.5L15,2l-1.5,1.5L12,2l-1.5,1.5L9,2 7.5,3.5 6,2 4.5,3.5 3,2v20l1.5,-1.5L6,22l1.5,-1.5L9,22l1.5,-1.5L12,22l1.5,-1.5L15,22l1.5,-1.5L18,22l1.5,-1.5L21,22V2l-1.5,1.5z" +
        "M18,17H6v-2h12v2zM18,13H6v-2h12v2zM18,9H6V7h12v2z") }

    /** Material "bolt", for Quick print. */
    val Bolt: ImageVector by lazy { filled("Bolt", "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z") }

    /** Material "dashboard", for the Sopifo dashboard (app.sopifo.com) inside the app. */
    val Dashboard: ImageVector by lazy { filled("Dashboard", "M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z") }

    /** Material "open_in_new", for "Open in Chrome". */
    val OpenInNew: ImageVector by lazy { filled("OpenInNew", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
        "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z") }

    // Rich text editor (Quick print → Text): Material "format_*" icons.
    val Bold: ImageVector by lazy { filled("Bold", "M15.6,10.79c0.97,-0.67 1.65,-1.77 1.65,-2.79 0,-2.26 -1.75,-4 -4,-4L7,4v14h7.04c2.09,0 3.71,-1.7 3.71,-3.79 0,-1.52 -0.86,-2.82 -2.15,-3.42zM10,6.5h3c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5h-3v-3zM13.5,15.5L10,15.5v-3h3.5c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5z") }
    val Italic: ImageVector by lazy { filled("Italic", "M10,4v3h2.21l-3.42,8H6v3h8v-3h-2.21l3.42,-8H18V4z") }
    val Underlined: ImageVector by lazy { filled("Underlined", "M12,17c3.31,0 6,-2.69 6,-6V3h-2.5v8c0,1.93 -1.57,3.5 -3.5,3.5S8.5,12.93 8.5,11V3H6v8c0,3.31 2.69,6 6,6zM5,19v2h14v-2H5z") }
    val AlignLeft: ImageVector by lazy { filled("AlignLeft", "M15,15H3v2h12v-2zM15,7H3v2h12V7zM3,13h18v-2H3v2zM3,21h18v-2H3v2zM3,3v2h18V3H3z") }
    val AlignCenter: ImageVector by lazy { filled("AlignCenter", "M7,15v2h10v-2H7zM3,21h18v-2H3v2zM3,13h18v-2H3v2zM7,7v2h10V7H7zM3,3v2h18V3H3z") }
    val AlignRight: ImageVector by lazy { filled("AlignRight", "M3,21h18v-2H3v2zM9,17h12v-2H9v2zM3,13h18v-2H3v2zM9,9h12V7H9v2zM3,3v2h18V3H3z") }
    val BulletList: ImageVector by lazy {
        filled(
            "BulletList",
            "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z",
        )
    }

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
