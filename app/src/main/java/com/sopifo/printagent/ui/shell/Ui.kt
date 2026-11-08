package com.sopifo.printagent.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Big icon tile with a short bold label: the app's main building block (beginner-friendly). */
@Composable
fun Tile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    sub: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(34.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
