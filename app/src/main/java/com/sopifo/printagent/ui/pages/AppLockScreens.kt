package com.sopifo.printagent.ui.pages

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.security.AppLock
import com.sopifo.printagent.security.PinHasher
import com.sopifo.printagent.ui.screens.ScreenTitle
import com.sopifo.printagent.ui.screens.SectionCard

/** Fingerprint / face unlock is set up on this phone. */
private fun biometricAvailable(activity: FragmentActivity?): Boolean =
    activity != null &&
        BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

private fun askBiometric(activity: FragmentActivity, onSuccess: () -> Unit) = runCatching {
    // Any problem showing the prompt just leaves the PIN to unlock with.
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Sopifo Print")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build(),
    )
}

/**
 * Shows [content] only when the app lock is off or unlocked; otherwise the PIN pad (and the
 * fingerprint prompt, if allowed). Forgetting the PIN means re-pairing the phone.
 */
@Composable
fun LockGate(lock: AppLock, onRepair: () -> Unit, content: @Composable () -> Unit) {
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val locked by lock.locked.collectAsStateWithLifecycle()
    if (!enabled || !locked) {
        content()
        return
    }
    val activity = LocalContext.current as? FragmentActivity
    val biometric by lock.biometric.collectAsStateWithLifecycle()
    val canBiometric = biometric && biometricAvailable(activity)
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRepair by remember { mutableStateOf(false) }
    LaunchedEffect(canBiometric) {
        if (canBiometric) askBiometric(activity!!) { lock.unlockedByBiometric() }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
        Text("Enter your PIN", style = MaterialTheme.typography.titleLarge)
        Text("Orders, shipping and print history are locked on this phone.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> if (v.length <= 8 && v.all(Char::isDigit)) pin = v; error = null },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.testTag("lock_pin"),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val wait = lock.waitSeconds()
                error = when {
                    wait > 0 -> "Too many wrong tries: wait $wait seconds"
                    lock.unlock(pin) -> null
                    else -> lock.waitSeconds().let { if (it > 0) "Too many wrong tries: wait $it seconds" else "Wrong PIN" }
                }
                pin = ""
            },
            enabled = pin.length >= 4,
            modifier = Modifier.testTag("lock_unlock"),
        ) { Text("Unlock") }
        if (canBiometric) OutlinedButton(onClick = { askBiometric(activity!!) { lock.unlockedByBiometric() } }) { Text("Use fingerprint") }
        TextButton(onClick = { confirmRepair = true }) { Text("Forgot PIN?") }
    }
    if (confirmRepair) {
        AlertDialog(
            onDismissRequest = { confirmRepair = false },
            title = { Text("Forgot your PIN?") },
            text = { Text("Re-pair this phone: it signs out, the lock is removed, and you pair it again from the dashboard (Cloud Print → Pairing).") },
            confirmButton = { TextButton(onClick = { confirmRepair = false; onRepair() }) { Text("Re-pair this phone") } },
            dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text("Cancel") } },
        )
    }
}

/** More → App lock: set or change the PIN, fingerprint, or turn the lock off. */
@Composable
fun AppLockSettings(lock: AppLock) {
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val biometric by lock.biometric.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("App lock")
        Text(
            "Locks Orders, Shipping and print history (they show customer names and phones). " +
                "Printing sent from the dashboard keeps working while locked. The PIN is kept on this phone only.",
            style = MaterialTheme.typography.bodySmall,
        )
        SectionCard(title = if (enabled) "Change PIN" else "Set a PIN") {
            PinField("New PIN (4–8 digits)", pin) { pin = it }
            PinField("Repeat it", confirm) { confirm = it }
            Button(
                onClick = {
                    note = when {
                        !PinHasher.isValid(pin) -> "The PIN must be 4 to 8 digits"
                        pin != confirm -> "The two PINs don't match"
                        else -> { lock.setPin(pin); "PIN saved: the lock is on" }
                    }
                    pin = ""; confirm = ""
                },
                modifier = Modifier.testTag("lock_save"),
            ) { Text(if (enabled) "Change PIN" else "Turn lock on") }
        }
        if (enabled) {
            SectionCard(title = "Fingerprint / face unlock") {
                if (biometricAvailable(activity)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Unlock with fingerprint or face as well as the PIN", Modifier.weight(1f))
                        Switch(checked = biometric, onCheckedChange = { lock.setBiometric(it) })
                    }
                } else {
                    Text("No fingerprint or face unlock is set up on this phone.", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard(title = "Turn the lock off") {
                var current by remember { mutableStateOf("") }
                PinField("Current PIN", current) { current = it }
                OutlinedButton(onClick = {
                    note = if (lock.disable(current)) "The lock is off" else "Wrong PIN"
                    current = ""
                }) { Text("Turn lock off") }
            }
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 8 && v.all(Char::isDigit)) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
