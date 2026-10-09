package com.folio.batterysync

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ComposableScope
import expo.modules.kotlin.views.ComposeProps
import expo.modules.kotlin.views.ExpoComposeView
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

// React 负责状态与操作，Compose 负责 miuix 原生界面。
class DashboardModel : Record {
    @Field var phoneName: String = ""
    @Field var phoneLevel: Int? = null
    @Field var phoneStatus: String = ""
    @Field var wearableName: String = ""
    @Field var wearableLevel: Int? = null
    @Field var wearableStatus: String = ""
    @Field var phoneCharging: Boolean = false
    @Field var wearableCharging: Boolean = false
    @Field var lastRead: String = ""
    @Field var backgroundRunning: Boolean = false
    @Field var controlsEnabled: Boolean = false
    @Field var readEnabled: Boolean = false
    @Field var reading: Boolean = false
    @Field var computerConnected: Boolean = false
    @Field var computerDevices: List<ComputerDeviceModel> = emptyList()
    @Field var noticeTitle: String = ""
    @Field var notice: String = ""
}

class ComputerDeviceModel : Record {
    @Field var name: String = ""
    @Field var category: String = "accessory"
    @Field var level: Int = 0
    @Field var charging: Boolean? = null
}

class DashboardProps : ComposeProps

class BatteryDashboardView(context: Context, appContext: AppContext) :
    ExpoComposeView<DashboardProps>(context, appContext, withHostingView = true) {
    override val props = DashboardProps()
    var model by mutableStateOf(DashboardModel())
    var labels by mutableStateOf<Map<String, String>>(emptyMap())
    private val onAction by EventDispatcher()

    @Composable
    override fun ComposableScope.Content() {
        MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            if (labels.isNotEmpty()) BatteryDashboard(model, labels) { onAction(mapOf("action" to it)) }
        }
    }
}

@Composable
private fun BatteryDashboard(model: DashboardModel, labels: Map<String, String>, action: (String) -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val margin = BasicComponentDefaults.InsideMargin.calculateTopPadding()
    val showNotice = remember { mutableStateOf(false) }
    LaunchedEffect(model.notice) { showNotice.value = model.notice.isNotEmpty() }
    fun label(key: String) = labels[key].orEmpty()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = label("title"), scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(margin),
        ) {
            item {
                BoxWithConstraints(Modifier.padding(horizontal = 26.dp)) {
                    val minimumWidth = with(LocalDensity.current) { MiuixTheme.textStyles.title1.fontSize.toDp() } * 3.5f + margin * 2
                    val tiles: @Composable (Modifier) -> Unit = { modifier ->
                        DeviceTile(label("phone"), model.phoneName, model.phoneLevel, model.phoneCharging, model.phoneStatus, R.drawable.ic_phone, modifier)
                        DeviceTile(label("wearable"), model.wearableName, model.wearableLevel, model.wearableCharging, model.wearableStatus, R.drawable.ic_watch, modifier)
                    }
                    if (maxWidth >= minimumWidth * 2 + margin) {
                        Row(horizontalArrangement = Arrangement.spacedBy(margin)) { tiles(Modifier.weight(1f)) }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(margin)) { tiles(Modifier.fillMaxWidth()) }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(margin)) {
                    Text(model.lastRead, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button(onClick = { action("refresh") }, enabled = model.readEnabled,
                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(label(if (model.reading) "reading" else "refresh"))
                    }
                }
            }
            item { SmallTitle(label("computerDevices")) }
            item {
                Card(Modifier.padding(horizontal = 26.dp)) {
                    if (model.computerDevices.isEmpty()) {
                        BasicComponent(summary = label(if (model.computerConnected) "computerEmpty" else "computerWaiting"))
                    } else {
                        model.computerDevices.forEach { device ->
                            BasicComponent(
                                title = device.name,
                                summary = label("device.${device.category}"),
                                leftAction = { Icon(painterResource(deviceIcon(device.category)), contentDescription = null) },
                                rightActions = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(margin / 2)) {
                                        Text("${device.level}%", style = MiuixTheme.textStyles.body1)
                                        BatteryGauge(device.level, device.charging == true, Modifier.width(ButtonDefaults.MinHeight), showNumber = false)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item { SmallTitle(label("sync")) }
            item {
                Card(Modifier.padding(horizontal = 26.dp)) {
                    SuperSwitch(checked = model.backgroundRunning,
                        onCheckedChange = { action(if (it) "startBackground" else "stopBackground") },
                        title = label("background"),
                        summary = label(if (model.backgroundRunning) "backgroundActive" else "backgroundInactive"),
                        enabled = model.controlsEnabled)
                }
            }
            item { SmallTitle(label("connections")) }
            item {
                Card(Modifier.padding(horizontal = 26.dp)) {
                    SuperArrow(title = label("openHost"), summary = label("connectionHint"), enabled = model.controlsEnabled, onClick = { action("openHost") })
                    SuperArrow(title = label("authorize"), summary = label("authorizationHint"), enabled = model.readEnabled, onClick = { action("authorize") })
                    SuperArrow(title = label("openSettings"), summary = label("settingsHint"), enabled = model.controlsEnabled, onClick = { action("openSettings") })
                    SuperArrow(title = label("shareStatus"), summary = label("shareHint"), enabled = model.controlsEnabled, onClick = { action("share") })
                }
            }
            item { Column(Modifier.height(margin)) {} }
        }
        SuperDialog(show = showNotice, title = model.noticeTitle, summary = model.notice,
            onDismissRequest = { action("dismiss") }) {
            Button(onClick = { showNotice.value = false; action("dismiss") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(label("dismiss"))
            }
        }
    }
}

@Composable
private fun DeviceTile(kind: String, name: String, level: Int?, charging: Boolean, status: String, icon: Int, modifier: Modifier) {
    val margin = BasicComponentDefaults.InsideMargin.calculateTopPadding()
    Card(modifier = modifier) {
        Column(Modifier.padding(BasicComponentDefaults.InsideMargin), verticalArrangement = Arrangement.spacedBy(margin / 2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(margin / 2)) {
                Icon(painterResource(icon), contentDescription = null)
                Text(kind, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            BatteryGauge(level, charging, Modifier.fillMaxWidth())
            Text(name, style = MiuixTheme.textStyles.headline1)
            Text(status, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

// 电量填充与文字反色共用裁切边界，未知读数不绘制填充。
@Composable
private fun BatteryGauge(level: Int?, charging: Boolean, modifier: Modifier, showNumber: Boolean = true) {
    val foreground = MiuixTheme.colorScheme.onSurface
    val cutout = MiuixTheme.colorScheme.surfaceContainer
    val text = if (level == null) "—" else level.toString()
    val measured = rememberTextMeasurer().measure(AnnotatedString(text), style = MiuixTheme.textStyles.title1)
    BoxWithConstraints(modifier) {
        val height = if (showNumber) maxOf(maxWidth / 2.1f, with(LocalDensity.current) { measured.size.height.toDp() } * 1.35f) else maxWidth / 2.1f
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = level?.let { "$it%" }.orEmpty() }) {
            val bodyWidth = size.width * .9f
            val bodyHeight = size.height * .8f
            val top = (size.height - bodyHeight) / 2
            val radius = bodyHeight * .23f
            val shape = Path().apply { addRoundRect(RoundRect(0f, top, bodyWidth, top + bodyHeight, CornerRadius(radius))) }
            val filledWidth = bodyWidth * ((level ?: 0) / 100f)
            val boltWidth = if (charging) bodyHeight * .21f else 0f
            val gap = if (charging) bodyHeight * .05f else 0f
            val textLeft = (bodyWidth - measured.size.width - boltWidth - gap) / 2
            val textTop = (size.height - measured.size.height) / 2
            val boltLeft = if (showNumber) textLeft + measured.size.width + gap else (bodyWidth - boltWidth) / 2
            val boltTop = top + bodyHeight * .18f
            val boltHeight = bodyHeight * .64f
            val bolt = Path().apply {
                moveTo(boltLeft + boltWidth * .68f, boltTop)
                lineTo(boltLeft, boltTop + boltHeight * .56f)
                lineTo(boltLeft + boltWidth * .42f, boltTop + boltHeight * .56f)
                lineTo(boltLeft + boltWidth * .3f, boltTop + boltHeight)
                lineTo(boltLeft + boltWidth, boltTop + boltHeight * .42f)
                lineTo(boltLeft + boltWidth * .57f, boltTop + boltHeight * .42f)
                close()
            }
            drawRoundRect(foreground.copy(alpha = .16f), Offset(bodyWidth + size.width * .025f, size.height * .36f), Size(size.width * .055f, size.height * .28f), CornerRadius(radius / 2))
            clipPath(shape) {
                drawRect(foreground.copy(alpha = .16f))
                drawRect(foreground, size = Size(filledWidth, size.height))
                if (showNumber) drawText(measured, color = foreground, topLeft = Offset(textLeft, textTop))
                if (charging) drawPath(bolt, foreground)
                clipRect(right = filledWidth) {
                    if (showNumber) drawText(measured, color = cutout, topLeft = Offset(textLeft, textTop))
                    if (charging) drawPath(bolt, cutout)
                }
            }
        }
    }
}

private fun deviceIcon(category: String) = when (category) {
    "computer" -> R.drawable.ic_computer
    "keyboard" -> R.drawable.ic_keyboard
    "mouse" -> R.drawable.ic_mouse
    "trackpad" -> R.drawable.ic_trackpad
    "headphones" -> R.drawable.ic_headphones
    else -> R.drawable.ic_bluetooth
}
