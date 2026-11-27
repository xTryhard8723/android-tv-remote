package dev.krist.shieldremote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krist.shieldremote.RemoteController
import dev.krist.shieldremote.net.DiscoveredTv
import dev.krist.shieldremote.net.Discovery
import kotlinx.coroutines.launch

private data class Target(val host: String, val name: String, val serviceName: String?)

@Composable
fun PairingScreen(controller: RemoteController, canGoBack: Boolean, preselectHost: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<DiscoveredTv>() }
    var target by remember { mutableStateOf<Target?>(null) }
    var stage by remember { mutableStateOf(0) } // 0 = pick, 1 = connecting, 2 = code, 3 = verifying
    var error by remember { mutableStateOf<String?>(null) }
    var manualIp by remember { mutableStateOf(controller.state.value.device?.host ?: "") } // prefill last used IP

    DisposableEffect(Unit) {
        val d = Discovery(context)
        d.start({ tv -> found.removeAll { it.serviceName == tv.serviceName }; found.add(tv) }, { name -> found.removeAll { it.serviceName == name } })
        onDispose { d.stop(); if (stage != 4) controller.cancelPairing() }
    }

    fun begin(t: Target) {
        target = t; stage = 1; error = null
        scope.launch {
            try {
                val serverName = controller.beginPairing(t.host)
                if (t.serviceName == null && !serverName.isNullOrBlank()) target = t.copy(name = serverName)
                stage = 2
            } catch (e: Exception) {
                stage = 0; error = "Couldn't reach ${t.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(preselectHost) { if (preselectHost != null && target == null) begin(Target(preselectHost, controller.state.value.device?.name ?: preselectHost, controller.state.value.device?.serviceName)) }

    PairingContent(
        stage = stage, found = found, targetName = target?.name, targetHost = target?.host, error = error,
        manualIp = manualIp, onManualIp = { manualIp = it }, canGoBack = canGoBack,
        onBack = { if (stage >= 2) { controller.cancelPairing(); stage = 0 } else onBack() },
        onPick = { tv -> if (stage == 0) begin(Target(tv.host, tv.serviceName, tv.serviceName)) },
        onManual = { h -> if (stage == 0) begin(Target(h, h, null)) },
        onNewCode = { controller.cancelPairing(); target?.let { begin(Target(it.host, it.name, it.serviceName)) } },
        onCode = { code ->
            val t = target
            if (t != null) {
                stage = 3; error = null
                scope.launch {
                    try {
                        controller.finishPairing(code, t.host, t.name, t.serviceName)
                        stage = 4; onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Pairing failed"
                        stage = if (e is dev.krist.shieldremote.protocol.PairingSecret.InvalidCode) 2 else 0
                        if (stage == 0) controller.cancelPairing()
                    }
                }
            }
        },
    )
}

/** Stateless pairing UI (also rendered by the screenshot tests). */
@Composable
fun PairingContent(
    stage: Int, found: List<DiscoveredTv>, targetName: String?, targetHost: String?, error: String?,
    manualIp: String, onManualIp: (String) -> Unit, canGoBack: Boolean,
    onBack: () -> Unit, onPick: (DiscoveredTv) -> Unit, onManual: (String) -> Unit, onNewCode: () -> Unit, onCode: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.BgTop, Palette.Bg)))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (canGoBack || stage >= 2) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
                Text(if (stage >= 2) "Enter the code" else "Connect your Shield", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            }
            Spacer(Modifier.height(10.dp))
            AnimatedContent(stage >= 2, label = "stage") { codeStage ->
                if (!codeStage) Column {
                    Text("Make sure your phone and the Shield are on the same Wi-Fi network.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Found on your network", style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.weight(1f))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (found.isEmpty()) Text("Searching… (Shield must be switched on)", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    found.forEach { tv ->
                        DeviceCard(tv.serviceName, tv.host, busy = stage == 1 && targetHost == tv.host) { onPick(tv) }
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Or enter the Shield's IP address", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    Text("Shield: Settings → Device Preferences → About → Status", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            manualIp, { onManualIp(it.filter { c -> c.isDigit() || c == '.' || c == ':' || c.isLetter() || c == '-' }) },
                            modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. 192.168.1.50", color = Palette.TextDim.copy(alpha = 0.8f)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, cursorColor = Palette.Accent, focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text),
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { val h = manualIp.trim(); if (h.isNotEmpty()) onManual(h) }, enabled = stage == 0 && manualIp.isNotBlank()) { Text("Connect") }
                    }
                } else Column {
                    Text("A code is now shown on your TV for ${targetName ?: "the Shield"}. Type it below.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    CodeEntry(enabled = stage == 2, onComplete = onCode)
                    if (stage == 3) Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
                        Text("  Pairing…", color = Palette.TextDim)
                    }
                    TextButton(onClick = onNewCode, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Show a new code", color = Palette.Accent)
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DeviceCard(name: String, host: String, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Surface).border(1.dp, Palette.Stroke, shape).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tv, null, tint = Palette.Accent)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Text(host, color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Accent)
    }
}

@Composable
private fun CodeEntry(enabled: Boolean, onComplete: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(enabled) { if (enabled && code.length == 6) code = "" }
    BasicTextField(
        value = code,
        onValueChange = { v ->
            val f = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
            code = f
            if (f.length == 6 && enabled) onComplete(f)
        },
        enabled = enabled,
        modifier = Modifier.focusRequester(focus),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(6) { i ->
                    val ch = code.getOrNull(i)?.toString() ?: ""
                    val focused = i == code.length
                    Box(
                        Modifier.size(width = 48.dp, height = 60.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface)
                            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.Accent else Palette.Stroke, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(ch, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = Palette.Text) }
                }
            }
        },
    )
}
