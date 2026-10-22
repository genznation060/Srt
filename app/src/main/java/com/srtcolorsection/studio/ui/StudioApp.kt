package com.srtcolorsection.studio.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srtcolorsection.studio.domain.FilenamePattern
import com.srtcolorsection.studio.domain.Palette
import com.srtcolorsection.studio.domain.PngResolutionPreset
import com.srtcolorsection.studio.domain.SectionEngine
import com.srtcolorsection.studio.domain.ThemeMode
import com.srtcolorsection.studio.domain.TimelineSection
import com.srtcolorsection.studio.domain.VideoEncoderChoice
import com.srtcolorsection.studio.domain.VideoPreset
import com.srtcolorsection.studio.domain.formatClock
import com.srtcolorsection.studio.domain.formatDuration
import com.srtcolorsection.studio.domain.formatRange
import com.srtcolorsection.studio.domain.parseClock
import com.srtcolorsection.studio.export.ExportKind
import com.srtcolorsection.studio.export.ExportUiState
import com.srtcolorsection.studio.export.RecordState
import com.srtcolorsection.studio.export.ScreenRecordService
import com.srtcolorsection.studio.ui.theme.StudioTheme

private enum class Tab { TIMELINE, EXPORT, SETTINGS }

@Composable
fun StudioApp(model: StudioViewModel = viewModel()) {
    val state by model.ui.collectAsStateWithLifecycle()
    StudioTheme(state.settings.theme) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.banner) {
            val text = state.banner ?: return@LaunchedEffect
            snackbar.showSnackbar(text)
            model.dismissBanner()
        }
        var tab by remember { mutableStateOf(Tab.TIMELINE) }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { StudioTopBar(state, model) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == Tab.TIMELINE, { tab = Tab.TIMELINE }, label = { Text("Timeline") }, icon = { Text("TL") })
                    NavigationBarItem(tab == Tab.EXPORT, { tab = Tab.EXPORT }, label = { Text("Export") }, icon = { Text("EX") })
                    NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, label = { Text("Settings") }, icon = { Icon(Icons.Default.Settings, null) })
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.TIMELINE -> TimelinePage(state, model)
                    Tab.EXPORT -> ExportPage(state, model)
                    Tab.SETTINGS -> SettingsPage(state, model)
                }
                ExportOverlay(state.export, model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioTopBar(state: StudioUi, model: StudioViewModel) {
    TopAppBar(
        title = { Text(state.project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            TextButton(onClick = model::undo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = model::redo, enabled = state.canRedo) { Text("Redo") }
        },
    )
}

@Composable
private fun TimelinePage(state: StudioUi, model: StudioViewModel) {
    val context = LocalContext.current
    val openSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            model.importUri(uri)
        }
    }
    var editing by remember { mutableStateOf<TimelineSection?>(null) }
    var pendingDelete by remember { mutableStateOf<TimelineSection?>(null) }
    val numbered = remember(state.project.sections) { SectionEngine.numbered(state.project.sections) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("SRT Color Section Studio", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Import a subtitle file, color the timeline, then export section PNGs or a clean H.264 video.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openSrt.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream")) }) {
                    Text("Import SRT")
                }
                OutlinedButton(onClick = model::loadSample, enabled = !state.busyImport) { Text("Sample") }
            }
            if (state.busyImport) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            SummaryRow(state)
            if (state.project.warnings.isNotEmpty()) {
                Text(
                    state.project.warnings.take(4).joinToString("\n"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TimelineStrip(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::addSection) { Icon(Icons.Default.Add, null); Text("Section") }
                OutlinedButton(onClick = model::regroup, enabled = state.project.cues.isNotEmpty()) { Text("Regroup") }
            }
        }
        items(numbered, key = { it.second.id }) { (number, section) ->
            SectionCard(
                number = number,
                section = section,
                checked = section.id in state.selected,
                onCheck = { model.toggleSelected(section.id) },
                onEdit = { editing = section },
            )
        }
        if (state.project.sections.isEmpty()) {
            item { Text("No sections yet. Import an SRT file to build the timeline.") }
        }
    }
    editing?.let { section ->
        SectionEditor(
            section = section,
            onDismiss = { editing = null },
            onRename = { model.rename(section.id, it) },
            onColor = { model.recolor(section.id, it) },
            onTimes = { start, end -> model.updateTimes(section.id, start, end) },
            onNotes = { model.updateNotes(section.id, it) },
            onSplit = { model.split(section.id, it); editing = null },
            onMerge = { model.merge(section.id); editing = null },
            onMove = { model.move(section.id, it) },
            onDelete = { pendingDelete = section; editing = null },
        )
    }
    pendingDelete?.let { section ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${section.name}?") },
            text = { Text("The other sections stay as they are. You can undo this.") },
            confirmButton = {
                TextButton(onClick = { model.delete(section.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SummaryRow(state: StudioUi) {
    val project = state.project
    Text(
        "${project.cues.size} subtitles · ${project.sections.size} sections · ${formatDuration(project.contentDurationMs)}",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "Export length ${formatClock(project.exportDurationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TimelineStrip(state: StudioUi) {
    val sections = state.project.sections
    val end = state.project.exportDurationMs.coerceAtLeast(1L)
    Canvas(
        Modifier.fillMaxWidth().height(36.dp).padding(vertical = 6.dp),
    ) {
        drawRoundRect(color = Color(0x334C6EF5), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        sections.forEach { section ->
            val x = size.width * (section.startMs.coerceAtLeast(0L).toFloat() / end)
            val w = size.width * (section.durationMs.toFloat() / end)
            drawRoundRect(
                color = Color(section.colorArgb),
                topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(w.coerceAtLeast(3f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
        }
    }
}

@Composable
private fun SectionCard(
    number: Int,
    section: TimelineSection,
    checked: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(14.dp).background(Color(section.colorArgb), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("%03d  %s".format(number, section.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatRange(section.startMs, section.endMs), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(section.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = checked, onCheckedChange = { onCheck() })
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SectionEditor(
    section: TimelineSection,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onColor: (Int) -> Unit,
    onTimes: (Long, Long) -> Unit,
    onNotes: (String) -> Unit,
    onSplit: (Long) -> Unit,
    onMerge: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(section.id) { mutableStateOf(section.name) }
    var start by remember(section.id, section.startMs) { mutableStateOf(formatClock(section.startMs)) }
    var end by remember(section.id, section.endMs) { mutableStateOf(formatClock(section.endMs)) }
    var notes by remember(section.id) { mutableStateOf(section.notes) }
    var timeError by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Edit section", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onRename(name) }) { Text("Save name") }
            Text("Color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.colors.forEach { color ->
                    Box(
                        Modifier.size(32.dp).background(Color(color), CircleShape).clickable { onColor(color) },
                    )
                }
            }
            OutlinedTextField(start, { start = it }, label = { Text("Start") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(end, { end = it }, label = { Text("End") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            timeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val s = parseClock(start)
                val e = parseClock(end)
                if (s == null || e == null) timeError = "Use HH:MM:SS.mmm"
                else {
                    timeError = null
                    onTimes(s, e)
                }
            }) { Text("Save times") }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onNotes(notes) }) { Text("Save notes") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMove(-1) }) { Text("Up") }
                OutlinedButton(onClick = { onMove(1) }) { Text("Down") }
                OutlinedButton(onClick = { onSplit((section.startMs + section.endMs) / 2) }) { Text("Split") }
                OutlinedButton(onClick = onMerge) { Text("Merge next") }
            }
            TextButton(onClick = onDelete) { Text("Delete section") }
        }
    }
}

@Composable
private fun ExportPage(state: StudioUi, model: StudioViewModel) {
    val context = LocalContext.current
    var heavy by remember { mutableStateOf<ExportKind?>(null) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val createVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) model.startExport(ExportKind.VIDEO, uri.toString(), heavy == null)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.setPngFolder(uri)
    }
    val recordLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordService.start(context, result.resultCode, data)
        }
    }
    val record by ScreenRecordService.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Video", style = MaterialTheme.typography.titleLarge)
            Text("Rendered off-screen from the project. The phone UI is not recorded.", style = MaterialTheme.typography.bodySmall)
            PresetRow(state.settings.video.preset, model::applyVideoPreset)
            Text(videoSummary(state), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Silent AAC track", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.settings.video.audioEnabled,
                    onCheckedChange = { enabled -> model.updateSettings { it.copy(video = it.video.copy(audioEnabled = enabled, preset = VideoPreset.CUSTOM)) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fast start", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.settings.video.fastStart,
                    onCheckedChange = { enabled -> model.updateSettings { it.copy(video = it.video.copy(fastStart = enabled)) } },
                )
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    val long = state.project.exportDurationMs > 10 * 60 * 1000
                    if (long) heavy = ExportKind.VIDEO else createVideo.launch("timeline.mp4")
                },
                enabled = state.project.sections.isNotEmpty() && state.export !is ExportUiState.Running,
            ) { Text("Export MP4") }
        }
        item {
            Text("Section images", style = MaterialTheme.typography.titleLarge)
            Text(
                "Export each timeline section as an individual PNG with its section name and timestamps.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (state.settings.pngFolderUri.isBlank()) "No folder selected" else "Folder saved",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Choose image folder") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.settings.png.preset == PngResolutionPreset.HD_720, { model.applyPngResolution(PngResolutionPreset.HD_720) }, label = { Text("1280×720") })
                FilterChip(state.settings.png.preset == PngResolutionPreset.FHD_1080, { model.applyPngResolution(PngResolutionPreset.FHD_1080) }, label = { Text("1920×1080") })
            }
            ToggleRow("Timestamps", state.settings.png.showTimestamps) { value ->
                model.updateSettings { it.copy(png = it.png.copy(showTimestamps = value)) }
            }
            ToggleRow("Section number", state.settings.png.showSectionNumber) { value ->
                model.updateSettings { it.copy(png = it.png.copy(showSectionNumber = value)) }
            }
            ToggleRow("Section name", state.settings.png.showSectionName) { value ->
                model.updateSettings { it.copy(png = it.png.copy(showSectionName = value)) }
            }
            ToggleRow("Transparent background", state.settings.png.transparent) { value ->
                model.updateSettings { it.copy(png = it.png.copy(transparent = value)) }
            }
            ToggleRow("ZIP bundle", state.settings.png.zipBundle) { value ->
                model.updateSettings { it.copy(png = it.png.copy(zipBundle = value)) }
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    model.startExport(ExportKind.PNG_ALL, null, true)
                },
                enabled = state.project.sections.isNotEmpty() && state.export !is ExportUiState.Running,
            ) { Text("Export all section images") }
            OutlinedButton(
                onClick = { model.startExport(ExportKind.PNG_SELECTED, null, true) },
                enabled = state.selected.isNotEmpty() && state.export !is ExportUiState.Running,
            ) { Text("Export selected") }
        }
        item {
            Text("Record screen", style = MaterialTheme.typography.titleLarge)
            Text(
                "Optional and separate. Android will ask permission and show the system recording indicator. The capture includes status bars, navigation, and other apps. Clean timeline export does not use this.",
                style = MaterialTheme.typography.bodySmall,
            )
            when (val current = record) {
                is RecordState.Recording -> {
                    Text(current.message)
                    Button(onClick = { ScreenRecordService.stop(context) }) { Text("Stop recording") }
                }
                is RecordState.Saved -> Text(current.message)
                is RecordState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                RecordState.Idle -> {
                    Button(onClick = {
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        recordLauncher.launch(manager.createScreenCaptureIntent())
                    }) { Text("Record screen") }
                }
            }
        }
    }
    if (heavy != null) {
        AlertDialog(
            onDismissRequest = { heavy = null },
            title = { Text("Long export") },
            text = { Text("This timeline is long. A midrange phone may get warm. Continue with the current preset?") },
            confirmButton = {
                TextButton(onClick = {
                    heavy = null
                    createVideo.launch("timeline.mp4")
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { heavy = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsPage(state: StudioUi, model: StudioViewModel) {
    val video = state.settings.video
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("General", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.settings.theme == ThemeMode.SYSTEM, { model.setTheme(ThemeMode.SYSTEM) }, label = { Text("System") })
                FilterChip(state.settings.theme == ThemeMode.LIGHT, { model.setTheme(ThemeMode.LIGHT) }, label = { Text("Light") })
                FilterChip(state.settings.theme == ThemeMode.DARK, { model.setTheme(ThemeMode.DARK) }, label = { Text("Dark") })
            }
        }
        item {
            OutlinedTextField(
                state.settings.defaultProjectName,
                { value -> model.updateSettings { it.copy(defaultProjectName = value.take(60)) } },
                label = { Text("Default project name") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Text("Video export", style = MaterialTheme.typography.titleLarge) }
        item { Text("${video.width}×${video.height} · ${video.fps} fps · ${video.videoBitrate / 1_000_000.0} Mbps") }
        item { Text("Audio ${if (video.audioEnabled) "AAC-LC ${video.sampleRate} Hz stereo ${video.audioBitrate / 1000} kbps" else "off"}") }
        item {
            Text("Encoder")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    video.encoder == VideoEncoderChoice.DEVICE_MEDIACODEC,
                    { model.updateSettings { it.copy(video = it.video.copy(encoder = VideoEncoderChoice.DEVICE_MEDIACODEC, preset = VideoPreset.CUSTOM)) } },
                    label = { Text("Device H.264") },
                )
                FilterChip(
                    video.encoder == VideoEncoderChoice.FFMPEG_LIBX264,
                    { model.updateSettings { it.copy(video = it.video.copy(encoder = VideoEncoderChoice.FFMPEG_LIBX264, preset = VideoPreset.CUSTOM)) } },
                    label = { Text("libx264") },
                )
            }
            Text(
                "Device H.264 is MediaCodec. It is hardware only when the codec name is not a known software encoder. libx264 is FFmpeg software encoding and needs arm64 or x86_64.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Text("Filename pattern")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.settings.png.filenamePattern == FilenamePattern.NUMBER_NAME_TIMESTAMP, {
                    model.updateSettings { it.copy(png = it.png.copy(filenamePattern = FilenamePattern.NUMBER_NAME_TIMESTAMP)) }
                }, label = { Text("Name + time") })
                FilterChip(state.settings.png.filenamePattern == FilenamePattern.NUMBER_TIMESTAMP, {
                    model.updateSettings { it.copy(png = it.png.copy(filenamePattern = FilenamePattern.NUMBER_TIMESTAMP)) }
                }, label = { Text("Time") })
            }
            Text("Every PNG name includes the start and end timestamps.", style = MaterialTheme.typography.bodySmall)
        }
        item { Text("Performance", style = MaterialTheme.typography.titleLarge) }
        item {
            ToggleRow("Memory-conscious mode", state.settings.performance.memoryConscious) { value ->
                model.updateSettings { it.copy(performance = it.performance.copy(memoryConscious = value, safeMode = value)) }
            }
            ToggleRow("Low-storage warnings", state.settings.performance.lowStorageWarnings) { value ->
                model.updateSettings { it.copy(performance = it.performance.copy(lowStorageWarnings = value)) }
            }
            ToggleRow("Thermal warnings", state.settings.performance.thermalWarnings) { value ->
                model.updateSettings { it.copy(performance = it.performance.copy(thermalWarnings = value)) }
            }
            ToggleRow("Delete temp files", state.settings.performance.cleanupTemp) { value ->
                model.updateSettings { it.copy(performance = it.performance.copy(cleanupTemp = value)) }
            }
            Text("Rendering stays at one frame or one image at a time. Concurrent renders are fixed at 1.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::cleanupTemps) { Text("Clean temporary files now") }
        }
        item { Text("Diagnostics", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(state.diagnostics, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = model::refreshDiagnostics) { Text("Refresh") }
                TextButton(onClick = model::copyDiagnostics) { Text("Copy") }
            }
            TextButton(onClick = {
                model.updateSettings { AppSettings(defaultProjectName = it.defaultProjectName) }
            }) { Text("Reset settings") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PresetRow(current: VideoPreset, onPick: (VideoPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            VideoPreset.MOBILE_SAFE to "Mobile safe · 720p24 · 4 Mbps",
            VideoPreset.BALANCED to "Balanced · 720p30 · 6 Mbps",
            VideoPreset.FULL_HD to "Full HD · 1080p30 · 8 Mbps",
            VideoPreset.REFERENCE to "Reference · 1080p31 · 11.9 Mbps",
        ).forEach { (preset, label) ->
            FilterChip(selected = current == preset, onClick = { onPick(preset) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ExportOverlay(export: ExportUiState, model: StudioViewModel) {
    if (export is ExportUiState.Idle) return
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (export) {
            is ExportUiState.Running -> {
                Text(export.progress.phase, style = MaterialTheme.typography.headlineSmall)
                Text("${export.progress.current} / ${export.progress.total}")
                if (export.progress.filename.isNotBlank()) Text(export.progress.filename, maxLines = 2)
                LinearProgressIndicator(progress = { export.progress.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                Text("${export.progress.percent}% · saved ${export.progress.success} · failed ${export.progress.failed}")
                if (export.progress.detail.isNotBlank()) Text(export.progress.detail, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = model::cancelExport) { Text("Cancel export") }
            }
            is ExportUiState.Succeeded -> {
                Text("Export finished", style = MaterialTheme.typography.headlineSmall)
                Text(export.summary)
                if (export.diagnostic.isNotBlank()) Text(export.diagnostic, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { model.share(export.shareUris, export.mime) }) { Text("Share") }
                TextButton(onClick = model::clearExportResult) { Text("Close") }
            }
            is ExportUiState.Failed -> {
                Text("Export failed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                Text(export.message)
                if (export.diagnostic.isNotBlank()) Text(export.diagnostic, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::clearExportResult) { Text("Close") }
            }
            is ExportUiState.Cancelled -> {
                Text(export.message, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = model::clearExportResult) { Text("Close") }
            }
            ExportUiState.Idle -> Unit
        }
    }
}

private fun videoSummary(state: StudioUi): String {
    val video = state.settings.video
    val encoder = if (video.encoder == VideoEncoderChoice.FFMPEG_LIBX264) "libx264 software" else "device MediaCodec"
    return "${video.width}×${video.height}, ${video.fps} fps, about ${video.videoBitrate / 1_000_000.0} Mbps, $encoder."
}

@Composable
private fun UnusedProgress() {
    CircularProgressIndicator()
}
