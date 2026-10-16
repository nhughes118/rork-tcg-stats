package com.rork.tcgstatsandroid.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rork.tcgstatsandroid.data.CardIdentity
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.LoadState
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.CardImage
import com.rork.tcgstatsandroid.ui.components.CircleIconButton
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.navigation.AppActions
import com.rork.tcgstatsandroid.ui.screens.CardSearchSheet
import com.rork.tcgstatsandroid.ui.screens.PillButton
import com.rork.tcgstatsandroid.ui.theme.Palette

private enum class Permission { Unknown, Granted, Denied }

@Composable
fun ScanScreen(vm: AppViewModel, actions: AppActions, modifier: Modifier = Modifier, scanVm: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val state by vm.state.collectAsStateWithLifecycle()
    val detectedKey by scanVm.detectedKey.collectAsStateWithLifecycle()
    val torchOn by scanVm.isTorchOn.collectAsStateWithLifecycle()
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var cameraStatus by remember { mutableStateOf(CameraStatus.Starting) }
    var hasTorch by remember { mutableStateOf(false) }

    fun currentPermission(): Permission =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) Permission.Granted
        else Permission.Unknown

    var permission by remember { mutableStateOf(currentPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = if (granted) Permission.Granted else Permission.Denied
    }
    LaunchedEffect(Unit) {
        if (permission != Permission.Granted) launcher.launch(Manifest.permission.CAMERA)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (currentPermission() == Permission.Granted) permission = Permission.Granted
    }

    LaunchedEffect(state.matcher) { scanVm.matcher = state.matcher }
    LaunchedEffect(detectedKey) {
        if (detectedKey != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Light status bar icons over the dark camera.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = true
            scanVm.setTorch(false)
        }
    }

    val showCamera = permission == Permission.Granted && cameraStatus != CameraStatus.NoDevice && cameraStatus != CameraStatus.Failed

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when {
            showCamera -> {
                CameraPreview(
                    torchEnabled = torchOn,
                    onLines = scanVm::process,
                    onStatus = { status, torch ->
                        cameraStatus = status
                        hasTorch = torch
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                ScanFrameOverlay(isLocked = detectedKey != null, modifier = Modifier.fillMaxSize())
            }
            permission == Permission.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            permission == Permission.Denied -> Unavailable(
                title = "Camera access is off",
                message = "Allow camera access in Settings to scan cards, or search by name.",
                primaryLabel = "Open Settings",
                onPrimary = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    runCatching { context.startActivity(intent) }
                },
                onSearch = { isSearching = true },
                icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(44.dp)) },
            )
            else -> Unavailable(
                title = "No camera available",
                message = "This device has no camera we can use. You can still look up any card by name.",
                primaryLabel = null,
                onPrimary = {},
                onSearch = { isSearching = true },
                icon = { Icon(Icons.Filled.NoPhotography, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(44.dp)) },
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCamera && cameraStatus == CameraStatus.Ready && hasTorch) {
                CircleIconButton(
                    if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    label = "Flashlight",
                    background = Color.Black.copy(alpha = 0.45f),
                    tint = Color.White,
                    size = 46.dp,
                    onClick = scanVm::toggleTorch,
                )
            }
            Spacer(Modifier.weight(1f))
            CircleIconButton(
                Icons.Filled.Search,
                label = "Search cards",
                background = if (showCamera) Color.Black.copy(alpha = 0.45f) else Color.White,
                tint = if (showCamera) Color.White else Palette.Ink,
                size = 46.dp,
                onClick = { isSearching = true },
            )
        }

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val card = detectedKey?.let { state.dataset?.cards?.get(it) }
            AnimatedContent(
                targetState = card,
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn()) togetherWith
                        (slideOutVertically { it } + fadeOut())
                },
                contentKey = { it?.key },
                label = "detected",
            ) { detected ->
                if (detected != null) {
                    DetectedBanner(
                        card = detected,
                        usage = state.dataset?.recentUsage?.get(detected.key) ?: 0.0,
                        onView = {
                            actions.showResults(detected.key)
                            scanVm.clearDetection()
                        },
                        onDismiss = scanVm::clearDetection,
                    )
                } else if (showCamera && cameraStatus == CameraStatus.Ready) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StatusPill(state.loadState, onRetry = { vm.load(force = true) })
                    }
                } else {
                    Spacer(Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (isSearching) {
        CardSearchSheet(vm, onDismiss = { isSearching = false }, onSelect = { actions.showResults(it) })
    }
}

@Composable
private fun StatusPill(loadState: LoadState, onRetry: () -> Unit) {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .then(if (loadState is LoadState.Failed) Modifier.pressable(onClick = onRetry) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (loadState) {
            is LoadState.Loading -> {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Text("Loading tournament data · ${(loadState.progress * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            is LoadState.Failed -> {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Couldn't load data · Retry", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            else -> Text("Align your card in the frame", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun DetectedBanner(card: CardIdentity, usage: Double, onView: () -> Unit, onDismiss: () -> Unit) {
    Box {
        Row(
            Modifier.fillMaxWidth().cardSurface(24.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardImage(card, Modifier.width(48.dp).shadow(3.dp, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)), cornerRadius = 4.dp)
            Column(Modifier.weight(1f)) {
                Text("${card.name} detected", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 2)
                Text("${card.printLabel} · ${CardText.percent(usage)} of decks", fontSize = 15.sp, color = Palette.Muted, maxLines = 1)
            }
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .background(Palette.Blue, CircleShape)
                    .pressable(onClick = onView)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("View results", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(28.dp)
                .background(Palette.Canvas, CircleShape)
                .pressable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Palette.Muted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Unavailable(
    title: String,
    message: String,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    onSearch: () -> Unit,
    icon: @Composable () -> Unit,
) {
    CanvasBackground {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            icon()
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
            Text(message, fontSize = 17.sp, color = Palette.Muted, textAlign = TextAlign.Center)
            if (primaryLabel != null) {
                Button(onClick = onPrimary, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) { Text(primaryLabel) }
            }
            PillButton("Search by Name", Icons.Filled.Search, Palette.Blue, Color.White, Modifier.width(220.dp), onSearch)
        }
    }
}

/** Dimmed surround with card-shaped corner brackets and a scanning sweep. */
@Composable
private fun ScanFrameOverlay(isLocked: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweepPos",
    )
    val lockScale by animateFloatAsState(if (isLocked) 0.98f else 1f, spring(dampingRatio = 0.6f), label = "lock")
    val bracketColor = if (isLocked) Palette.Gold else Color.White

    Canvas(modifier) {
        val width = minOf(size.width * 0.78f, 360.dp.toPx())
        val height = width * 88f / 63f
        val rect = Rect(Offset((size.width - width) / 2, (size.height - height) / 2 - 30.dp.toPx()), Size(width, height))
        val corner = 22.dp.toPx()

        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(rect, CornerRadius(corner)))
        }
        drawPath(dim, Color.Black.copy(alpha = 0.35f))

        val outer = rect.inflate(6.dp.toPx())
        scale(lockScale, pivot = outer.center) {
            drawPath(bracketsPath(outer, 36.dp.toPx(), 24.dp.toPx()), bracketColor, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }

        if (!isLocked) {
            val y = rect.top + 16.dp.toPx() + (rect.height - 32.dp.toPx()) * sweep
            val lineWidth = rect.width - 24.dp.toPx()
            val start = rect.left + 12.dp.toPx()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Palette.Gold.copy(alpha = 0.25f), Color.Transparent), startX = start, endX = start + lineWidth),
                topLeft = Offset(start, y - 5.dp.toPx()),
                size = Size(lineWidth, 10.dp.toPx()),
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Palette.Gold.copy(alpha = 0.75f), Color.Transparent), startX = start, endX = start + lineWidth),
                topLeft = Offset(start, y - 1.dp.toPx()),
                size = Size(lineWidth, 2.dp.toPx()),
            )
        }
    }
}

private fun bracketsPath(r: Rect, len: Float, radius: Float): Path = Path().apply {
    moveTo(r.left, r.top + len + radius)
    lineTo(r.left, r.top + radius)
    quadraticTo(r.left, r.top, r.left + radius, r.top)
    lineTo(r.left + radius + len, r.top)

    moveTo(r.right - radius - len, r.top)
    lineTo(r.right - radius, r.top)
    quadraticTo(r.right, r.top, r.right, r.top + radius)
    lineTo(r.right, r.top + radius + len)

    moveTo(r.right, r.bottom - radius - len)
    lineTo(r.right, r.bottom - radius)
    quadraticTo(r.right, r.bottom, r.right - radius, r.bottom)
    lineTo(r.right - radius - len, r.bottom)

    moveTo(r.left + radius + len, r.bottom)
    lineTo(r.left + radius, r.bottom)
    quadraticTo(r.left, r.bottom, r.left, r.bottom - radius)
    lineTo(r.left, r.bottom - radius - len)
}
