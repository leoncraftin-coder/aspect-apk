package com.aspectstretch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Ratio(val a: Int, val b: Int) {
    val label: String get() = "$a:$b"
    val value: Double get() = max(a, b).toDouble() / min(a, b)
}

data class Size(val w: Int, val h: Int) {
    override fun toString(): String = "${w}x$h"
}

val PRESETS = listOf(
    Ratio(16, 10),
    Ratio(4, 3),
    Ratio(5, 4),
    Ratio(3, 2),
    Ratio(16, 9),
    Ratio(1, 1),
    Ratio(2, 1),
    Ratio(21, 9),
)

object AspectMath {
    const val MAX_RATIO = 4.0

    fun target(physical: Size, ratio: Ratio): Size {
        val big = max(physical.w, physical.h)
        val small = min(physical.w, physical.h)
        val physicalRatio = big.toDouble() / small
        val r = ratio.value

        val newBig: Int
        val newSmall: Int
        if (r < physicalRatio) {
            newBig = even(small * r)
            newSmall = small
        } else {
            newBig = big
            newSmall = even(big / r)
        }
        return if (physical.w <= physical.h) Size(newSmall, newBig) else Size(newBig, newSmall)
    }

    fun scaledDensity(physicalDpi: Int, physical: Size, target: Size): Int =
        (physicalDpi * target.w / physical.w.toDouble()).roundToInt().coerceAtLeast(120)

    private fun even(v: Double): Int {
        val n = v.roundToInt()
        return if (n % 2 == 0) n else n + 1
    }
}

object Wm {
    data class Out(val code: Int, val text: String) {
        val ok: Boolean
            get() = code == 0 &&
                !text.contains("exception", ignoreCase = true) &&
                !text.contains("denied", ignoreCase = true) &&
                !text.contains("not found", ignoreCase = true)
    }

    data class Info(
        val physical: Size,
        val override: Size?,
        val physicalDpi: Int?,
        val overrideDpi: Int?,
    ) {
        val current: Size get() = override ?: physical
        val currentDpi: Int? get() = overrideDpi ?: physicalDpi
    }

    private fun exec(args: List<String>): Out = try {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(8, TimeUnit.SECONDS)) {
            Out(p.exitValue(), text)
        } else {
            p.destroyForcibly()
            Out(-1, "timeout")
        }
    } catch (e: Exception) {
        Out(-1, e.message ?: "error")
    }

    private fun direct(cmd: String) = exec(listOf("sh", "-c", cmd))
    private fun root(cmd: String) = exec(listOf("su", "-c", cmd))

    fun run(cmd: String): Out {
        val d = direct(cmd)
        if (d.ok) return d
        val r = root(cmd)
        return if (r.ok) r else d
    }

    fun info(): Info? {
        val size = direct("wm size").text
        val physical = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(size)
            ?.let { Size(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: return null
        val override = Regex("Override size:\\s*(\\d+)x(\\d+)").find(size)
            ?.let { Size(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

        val density = direct("wm density").text
        val physicalDpi = Regex("Physical density:\\s*(\\d+)").find(density)?.groupValues?.get(1)?.toInt()
        val overrideDpi = Regex("Override density:\\s*(\\d+)").find(density)?.groupValues?.get(1)?.toInt()

        return Info(physical, override, physicalDpi, overrideDpi)
    }

    fun apply(size: Size, dpi: Int?): Out {
        val r = run("wm size $size")
        if (!r.ok) return r
        return if (dpi != null) run("wm density $dpi") else r
    }

    fun reset(): Out {
        val r = run("wm size reset")
        if (!r.ok) return r
        return run("wm density reset")
    }

    fun restore(prev: Info): Out {
        val s = if (prev.override == null) run("wm size reset") else run("wm size ${prev.override}")
        if (!s.ok) return s
        return if (prev.overrideDpi == null) run("wm density reset") else run("wm density ${prev.overrideDpi}")
    }
}

private fun Context.hasSecureSettings(): Boolean =
    checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var info by remember { mutableStateOf<Wm.Info?>(null) }
    var selected by remember { mutableStateOf<Ratio?>(PRESETS[0]) }
    var customA by remember { mutableStateOf("16") }
    var customB by remember { mutableStateOf("10") }
    var keepDpi by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf<Wm.Info?>(null) }
    var hasPermission by remember { mutableStateOf(ctx.hasSecureSettings()) }

    fun refresh() {
        scope.launch {
            info = withContext(Dispatchers.IO) { Wm.info() }
            hasPermission = ctx.hasSecureSettings()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val ratio: Ratio? = selected ?: run {
        val a = customA.toIntOrNull()
        val b = customB.toIntOrNull()
        if (a != null && b != null && a in 1..100 && b in 1..100) Ratio(a, b) else null
    }
    val ratioTooExtreme = ratio != null && ratio.value > AspectMath.MAX_RATIO
    val validRatio = if (ratioTooExtreme) null else ratio

    val current = info
    val target = if (current != null && validRatio != null) AspectMath.target(current.physical, validRatio) else null
    val targetDpi = if (keepDpi && current?.physicalDpi != null && target != null)
        AspectMath.scaledDensity(current.physicalDpi, current.physical, target) else null

    fun applyFormat() {
        val t = target ?: return
        val before = current ?: return
        scope.launch {
            busy = true
            message = null
            val r = withContext(Dispatchers.IO) { Wm.apply(t, targetDpi) }
            if (r.ok) {
                previous = before
            } else {
                message = "Не удалось применить: ${r.text.ifBlank { "нет доступа" }}. Выдайте разрешение (см. ниже)."
            }
            refresh()
            busy = false
        }
    }

    fun resetFormat() {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { Wm.reset() }
            message = if (r.ok) "Экран вернулся к родному формату" else "Не удалось сбросить: ${r.text}"
            refresh()
            busy = false
        }
    }

    previous?.let { prev ->
        ConfirmDialog(
            onKeep = { previous = null; message = "Формат сохранён" },
            onRevert = {
                previous = null
                scope.launch {
                    withContext(Dispatchers.IO) { Wm.restore(prev) }
                    message = "Прежний формат возвращён"
                    refresh()
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Растяжка экрана", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (current == null) {
                    Text("Читаю параметры экрана…")
                } else {
                    Text("Родной экран: ${current.physical}", fontFamily = FontFamily.Monospace)
                    Text("Сейчас: ${current.current}", fontFamily = FontFamily.Monospace)
                    current.currentDpi?.let { Text("Плотность: $it dpi", fontFamily = FontFamily.Monospace) }
                }
            }
        }

        Text("Формат", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESETS.forEach { p ->
                FilterChip(
                    selected = selected == p,
                    onClick = { selected = p },
                    label = { Text(p.label) },
                )
            }
            FilterChip(
                selected = selected == null,
                onClick = { selected = null },
                label = { Text("Свой") },
            )
        }

        if (selected == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customA,
                    onValueChange = { customA = it.filter(Char::isDigit).take(3) },
                    label = { Text("Длинная сторона") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Text(":", fontSize = 24.sp)
                OutlinedTextField(
                    value = customB,
                    onValueChange = { customB = it.filter(Char::isDigit).take(3) },
                    label = { Text("Короткая сторона") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            if (ratioTooExtreme) {
                Text(
                    "Слишком вытянутый формат. Максимум ${AspectMath.MAX_RATIO.toInt()}:1.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (target != null && current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(120.dp)
                        .aspectRatio(target.w.toFloat() / target.h),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {}
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Будет: $target", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    targetDpi?.let { Text("$it dpi", fontFamily = FontFamily.Monospace) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = keepDpi, onCheckedChange = { keepDpi = it })
            Column(Modifier.weight(1f)) {
                Text("Сохранять размер интерфейса")
                Text(
                    "Подбирает dpi, чтобы кнопки и текст не менялись по размеру",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { applyFormat() },
            enabled = target != null && !busy && previous == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Применить") }

        OutlinedButton(
            onClick = { resetFormat() },
            enabled = !busy && previous == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сбросить к родному формату") }

        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        if (!hasPermission) SetupCard(ctx.packageName) { clipboard.setText(AnnotatedString(it)) }
    }
}

@Composable
private fun SetupCard(pkg: String, onCopy: (String) -> Unit) {
    val cmd = "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Нужно разрешение", style = MaterialTheme.typography.titleMedium)
            Text(
                "Смена формата экрана требует особого разрешения. Его выдают один раз. " +
                    "Включите отладку по USB, подключите телефон к компьютеру и выполните команду:",
            )
            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            TextButton(onClick = { onCopy(cmd) }) { Text("Скопировать команду") }
            Text(
                "Если на телефоне есть root, приложение само запросит доступ при первом применении формата.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(onKeep: () -> Unit, onRevert: () -> Unit) {
    var left by remember { mutableIntStateOf(15) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1000)
            left--
        }
        onRevert()
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Оставить этот формат?") },
        text = { Text("Если ничего не нажать, прежний формат вернётся через $left с.") },
        confirmButton = { Button(onClick = onKeep) { Text("Оставить") } },
        dismissButton = { TextButton(onClick = onRevert) { Text("Вернуть") } },
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
