package com.krashkanter.extc

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024))
}

fun formatTime(milliseconds: Long): String = if(milliseconds < 1000) "$milliseconds ms" else
    String.format(Locale.getDefault(), "%.2f s", milliseconds / 1000.0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExperimentalCompressorApp(state: AppState, model: CompressorModel, dark: Boolean, toggleAppearance: () -> Unit) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::convertImage)
    }
    val viewerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::openImage)
    }
    val inputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::selectBatch)
    }
    val outputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::runBatch)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXTC_MIME)) { uri ->
        uri?.let(model::saveResult)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            model.dismissMessage()
        }
    }
    BackHandler(state.busy != null) { model.cancel() }
    BackHandler(state.viewer != null && state.busy == null) { model.closeViewer() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        AnimatedContent(
            targetState = state.viewer,
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 12 }) togetherWith fadeOut(tween(150)) },
            label = "Open image",
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { viewer ->
            if(viewer != null) {
                ImageViewer(viewer, model::closeViewer)
            } else {
                HomeScreen(dark, state.busy == null, toggleAppearance,
                    { imagePicker.launch(arrayOf("image/*")) },
                    { inputFolder.launch(null) },
                    { viewerPicker.launch(arrayOf("*/*")) })
            }
        }
    }
    state.result?.takeIf { state.busy == null }?.let { result ->
        AlertDialog(
            onDismissRequest = model::discardResult,
            title = { Text("Converted", style = MaterialTheme.typography.headlineMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(result.name, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatRow("Original", formatSize(result.sourceBytes))
                        StatRow("EXTC", formatSize(result.file.length()))
                        StatRow("Time", formatTime(result.elapsedMillis))
                        StatRow("Dimensions", "${result.width} \u00D7 ${result.height}")
                    }
                    val difference = result.file.length() - result.sourceBytes
                    Text(if(difference <= 0) "${formatSize(-difference)} smaller."
                         else "${formatSize(difference)} larger.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionIcon("Save", R.drawable.ic_save, { saveFile.launch(result.name) }, primary = true)
                    ActionIcon("Copy", R.drawable.ic_content_copy, model::copyResult)
                    ActionIcon("Discard", R.drawable.ic_delete_outline, model::discardResult)
                }
            }
        )
    }
    state.batch?.let { batch ->
        AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text("Output folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${batch.inputs.size} images in ${batch.name}")
                    Text("Subfolders and existing files are kept.")
                }
            },
            confirmButton = { ActionIcon("Select folder", R.drawable.ic_folder_open, { outputFolder.launch(null) }, primary = true) },
            dismissButton = { ActionIcon("Cancel", R.drawable.ic_close, model::cancel) }
        )
    }
    state.busy?.let { busy ->
        AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text(if(busy.total > 0) "Converting folder" else "Processing") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if(busy.total > 0) {
                        LinearProgressIndicator(progress = { busy.completed.toFloat() / busy.total }, modifier = Modifier.fillMaxWidth())
                        Text("${busy.completed} of ${busy.total} images processed", style = MaterialTheme.typography.titleMedium)
                    } else {
                        LoadingIndicator(Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                    }
                    Text(busy.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if(busy.total > 0) Text("Keep the app open.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { ActionIcon("Cancel", R.drawable.ic_close, model::cancel) }
        )
    }
    state.summary?.let { summary ->
        AlertDialog(
            onDismissRequest = model::dismissMessage,
            title = { Text(if(summary.cancelled) "Conversion stopped" else "Folder complete") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${summary.saved} saved \u00B7 ${summary.failed} failed")
                    Text("Total time ${formatTime(summary.elapsedMillis)}")
                    if(summary.cancelled) Text("Completed files are kept.")
                }
            },
            confirmButton = { ActionIcon("Done", R.drawable.ic_check, model::dismissMessage) }
        )
    }
    state.error?.let { message ->
        AlertDialog(onDismissRequest = model::dismissMessage,
            title = { Text("Couldn't finish") }, text = { Text(message) },
            confirmButton = { ActionIcon("OK", R.drawable.ic_check, model::dismissMessage) })
    }
}

@Composable
private fun HomeScreen(dark: Boolean, enabled: Boolean, toggleAppearance: () -> Unit,
                       pickImage: () -> Unit, pickFolder: () -> Unit, openFile: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if(wide) 64.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 36.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(painterResource(R.drawable.wordmark), contentDescription = "Experimental Compressor",
                        modifier = Modifier.size(width = 92.dp, height = 36.dp))
                    ActionIcon(if(dark) "Switch to light mode" else "Switch to dark mode",
                        if(dark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode, toggleAppearance)
                }
                Column {
                    Column {
                        Text("Experimental\nCompressor", style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = if(wide) 64.sp else 44.sp, lineHeight = if(wide) 70.sp else 50.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp))
                        Spacer(Modifier.height(36.dp))
                    }
                }
                Button(onClick = pickImage, enabled = enabled,
                    shape = RoundedCornerShape(30.dp), contentPadding = PaddingValues(24.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).animateContentSize()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Pick image", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ActionRow("Convert folder", enabled, pickFolder)
                Spacer(Modifier.height(12.dp))
                ActionRow("Open EXTC", enabled, openFile)
                Spacer(Modifier.height(36.dp))
                Text("Transparency becomes white.", Modifier.padding(bottom = 24.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, enabled: Boolean, action: () -> Unit) {
    FilledTonalButton(onClick = action, enabled = enabled, shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(24.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ImageViewer(image: OpenedImage, close: () -> Unit) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if(scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon("Back", R.drawable.ic_arrow_back, close)
            Text(image.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            ActionIcon("Fit image", R.drawable.ic_fit_screen, { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero })
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center) {
            Image(image.bitmap.asImageBitmap(), contentDescription = image.name,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .transformable(transform)
                    .pointerInput(image) { detectTapGestures(onDoubleTap = { scale = if(scale > 1f) 1f else 2.5f; offset = androidx.compose.ui.geometry.Offset.Zero }) })
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatRow("EXTC", formatSize(image.bytes))
            StatRow("Decoded pixels", formatSize(image.decodedBytes))
            Text("${image.bitmap.width} \u00D7 ${image.bitmap.height}  \u00B7  EXTC v${image.version}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionIcon(label: String, resource: Int, action: () -> Unit, primary: Boolean = false) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        if(primary) {
            FilledIconButton(onClick = action, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(resource), contentDescription = label)
            }
        } else {
            IconButton(onClick = action, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(resource), contentDescription = label)
            }
        }
    }
}
