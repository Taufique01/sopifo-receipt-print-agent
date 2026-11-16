package com.sopifo.printagent.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.print.QuickStart
import com.sopifo.printagent.ui.screens.printerStateGood
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood

/**
 * Not signed in (2.1.20): the app opens straight on printing. Sign in at the top, the printer,
 * three big print buttons, and one line about the free shop (its features in a sheet). Printing
 * on the phone is never limited; the shop card shows once after the 5th print (✕ = a week).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestHome(vm: AgentViewModel, onOpen: (StartRoute) -> Unit, onQuick: (QuickStart) -> Unit) {
    val t = strings()
    val context = LocalContext.current
    val settings = AppSettings.get(context)
    val lang by settings.lang.collectAsStateWithLifecycle()
    val configs by vm.printerConfigs.collectAsStateWithLifecycle()
    val status by vm.printerStatus.collectAsStateWithLifecycle()
    val labelSize by settings.labelSize.collectAsStateWithLifecycle()
    val prints by settings.prints.collectAsStateWithLifecycle()
    val hiddenUntil by settings.cardHiddenUntil.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp).testTag("guest_home"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Sopifo · language · Sign in.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            IconButton(onClick = { onOpen(StartRoute.ABOUT) }, modifier = Modifier.testTag("start_about")) {
                Icon(Icons.Outlined.Info, contentDescription = "About Sopifo", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = false,
                onClick = { settings.setLang(if (lang == "bn") "en" else "bn") },
                label = { Text(if (lang == "bn") "EN" else "বাংলা") },
                modifier = Modifier.testTag("lang_toggle"),
            )
            TextButton(onClick = { onOpen(StartRoute.LOGIN) }, modifier = Modifier.testTag("guest_sign_in")) {
                Text(t.signIn, fontWeight = FontWeight.Black)
            }
        }

        // The printer: name, ready dot, label size; red "Add printer" when none. Opens Printers.
        val label = configs.firstOrNull { it.role == PrinterRole.LABEL }
        val printer = label ?: configs.firstOrNull()
        val good = printer?.let { printerStateGood(status[it.role]) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (printer == null) StatusBad.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { onOpen(StartRoute.PRINTERS) }.testTag("guest_printer"),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(AppIcons.Print, contentDescription = null, tint = if (printer == null) StatusBad else MaterialTheme.colorScheme.onSurface)
                if (printer == null) {
                    Text(t.addPrinter, fontWeight = FontWeight.Black, color = StatusBad, modifier = Modifier.weight(1f))
                } else {
                    Surface(shape = CircleShape, color = if (good == false) StatusBad else StatusGood, modifier = Modifier.size(10.dp)) {}
                    Text(
                        if (label != null) "${printer.name} · ${labelSize.title.substringBefore(" (")}" else printer.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }

        // After the 5th print, once: what a free shop adds (✕ hides it for a week).
        if (GuestNudges.showShopCard(signedIn = false, prints.total, hiddenUntil, System.currentTimeMillis())) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("guest_shop_card")) {
                Column(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 12.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.cardTitle, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        IconButton(onClick = { settings.hideShopCard() }) { Icon(Icons.Filled.Close, contentDescription = t.dismiss) }
                    }
                    Text(t.cardBody, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onOpen(StartRoute.SIGNUP) }, modifier = Modifier.testTag("guest_card_signup")) { Text(t.createFreeShop) }
                }
            }
        }

        Text("⚡ ${t.guestPrintNow}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile(AppIcons.Pdf, t.pdf, "guest_quick_pdf", Modifier.weight(1f)) { onQuick(QuickStart.PDF) }
            BigTile(AppIcons.Photo, t.photo, "guest_quick_photo", Modifier.weight(1f)) { onQuick(QuickStart.PHOTO) }
            BigTile(AppIcons.Bold, t.text, "guest_quick_text", Modifier.weight(1f)) { onQuick(QuickStart.TEXT) }
        }
        Text(t.shareHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(12.dp))
        // One line about the free shop: tap it for the features; Sign up free goes straight to sign-up.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().testTag("guest_shop_line"),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().clickable { sheet = true }.testTag("guest_features"), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏪 ${t.shopLine}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
                Button(onClick = { onOpen(StartRoute.SIGNUP) }, modifier = Modifier.align(Alignment.End).testTag("guest_sign_up")) {
                    Text(t.signUpFree, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).testTag("guest_feature_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔒 ${t.withFreeShop}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                listOf(
                    Triple(AppIcons.Truck, t.featCourier, t.featCourierSub),
                    Triple(AppIcons.Print, t.featBulk, t.featBulkSub),
                    Triple(AppIcons.Chat, t.featCapture, t.featCaptureSub),
                    Triple(AppIcons.Sparkle, t.featAi, t.featAiSub),
                    Triple(AppIcons.Users, t.featFraud, t.featFraudSub),
                    Triple(AppIcons.Cash, t.featPos, t.featPosSub),
                ).forEach { (icon, title, sub) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sheet = false; onOpen(StartRoute.SIGNUP) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button(onClick = { sheet = false; onOpen(StartRoute.SIGNUP) }, modifier = Modifier.fillMaxWidth().testTag("sheet_sign_up")) {
                    Text(t.signUpFree, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { sheet = false; onOpen(StartRoute.LOGIN) }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("sheet_sign_in")) {
                    Text(t.orSignIn)
                }
            }
        }
    }
}

@Composable
private fun BigTile(icon: ImageVector, label: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick).testTag(tag),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
                Text(label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
