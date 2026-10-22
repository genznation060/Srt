package com.srtcolorsection.studio.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.srtcolorsection.studio.data.JsonStores
import com.srtcolorsection.studio.domain.AppSettings
import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.EditResult
import com.srtcolorsection.studio.domain.PngResolutionPreset
import com.srtcolorsection.studio.domain.Project
import com.srtcolorsection.studio.domain.SectionEngine
import com.srtcolorsection.studio.domain.SrtParser
import com.srtcolorsection.studio.domain.ThemeMode
import com.srtcolorsection.studio.domain.UndoStack
import com.srtcolorsection.studio.domain.VideoPreset
import com.srtcolorsection.studio.domain.applyPngPreset
import com.srtcolorsection.studio.domain.applyPreset
import com.srtcolorsection.studio.domain.ffmpegAbiSupported
import com.srtcolorsection.studio.export.DiagLog
import com.srtcolorsection.studio.export.ExportBus
import com.srtcolorsection.studio.export.ExportCoordinator
import com.srtcolorsection.studio.export.ExportForegroundService
import com.srtcolorsection.studio.export.ExportKind
import com.srtcolorsection.studio.export.ExportProgress
import com.srtcolorsection.studio.export.ExportRecovery
import com.srtcolorsection.studio.export.ExportRequest
import com.srtcolorsection.studio.export.ExportUiState
import com.srtcolorsection.studio.export.ffmpegLibrariesSafe
import com.srtcolorsection.studio.export.ffmpegVersionSafe
import com.srtcolorsection.studio.export.listAvcEncoders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudioUi(
    val project: Project = Project(),
    val settings: AppSettings = AppSettings(),
    val selected: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val banner: String? = null,
    val export: ExportUiState = ExportUiState.Idle,
    val diagnostics: String = "Loading encoder diagnostics…",
    val busyImport: Boolean = false,
)

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val stores = JsonStores(app)
    private val undo = UndoStack<Project>()
    private val _ui = MutableStateFlow(StudioUi())
    val ui: StateFlow<StudioUi> = _ui.asStateFlow()

    private val project: Project get() = _ui.value.project
    private val settings: AppSettings get() = _ui.value.settings

    init {
        viewModelScope.launch {
            ExportBus.state.collect { export -> _ui.update { it.copy(export = export) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loadedSettings = stores.loadSettings()
            val loadedProject = stores.loadProject() ?: Project(name = loadedSettings.defaultProjectName)
            _ui.update {
                it.copy(
                    settings = loadedSettings,
                    project = loadedProject,
                    banner = ExportRecovery.consumeNote(getApplication()),
                )
            }
            refreshDiagnostics()
        }
    }

    fun dismissBanner() { _ui.update { it.copy(banner = null) } }

    fun importText(source: String, text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(busyImport = true) }
            try {
                val parsed = SrtParser.parse(text)
                val sections = SectionEngine.group(
                    parsed.cues,
                    gapMs = settings.gapThresholdMs,
                    maxSectionMs = settings.maxSectionMs,
                )
                val next = Project(
                    name = source.substringAfterLast('/').substringBeforeLast('.').ifBlank { settings.defaultProjectName },
                    cues = parsed.cues,
                    sections = sections,
                    sourceLabel = source,
                    warnings = parsed.warnings.map { it.message },
                )
                replaceProject(next, remember = true)
                val note = if (parsed.cues.isEmpty()) {
                    "No valid subtitles were found."
                } else {
                    "Imported ${parsed.cues.size} subtitles into ${sections.size} sections." +
                        if (parsed.warnings.isEmpty()) "" else " ${parsed.warnings.size} warnings."
                }
                _ui.update { it.copy(banner = note) }
            } finally {
                _ui.update { it.copy(busyImport = false) }
            }
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busyImport = true) }
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Could not read the selected file")
                val name = uri.lastPathSegment ?: "subtitles.srt"
                _ui.update { it.copy(busyImport = false) }
                importText(name, text)
            } catch (error: Exception) {
                _ui.update { it.copy(banner = error.message ?: "Could not import that file", busyImport = false) }
            }
        }
    }

    fun loadSample() {
        viewModelScope.launch(Dispatchers.IO) {
            val text = getApplication<Application>().assets.open("sample_multilingual.srt")
                .bufferedReader(Charsets.UTF_8).readText()
            importText("sample_multilingual.srt", text)
        }
    }

    fun setProjectName(name: String) {
        edit { it.copy(name = name.take(60).ifBlank { settings.defaultProjectName }) }
    }

    fun rename(id: String, name: String) = editResult { SectionEngine.rename(it, id, name) }
    fun recolor(id: String, color: Int) = editResult { SectionEngine.recolor(it, id, color) }
    fun updateNotes(id: String, notes: String) = editResult { SectionEngine.updateNotes(it, id, notes) }
    fun updateTimes(id: String, startMs: Long, endMs: Long) = editResult { SectionEngine.updateTimes(it, id, startMs, endMs) }
    fun split(id: String, atMs: Long) = editResult { SectionEngine.split(it, id, atMs) }
    fun merge(id: String) = editResult { SectionEngine.mergeWithNext(it, id) }
    fun move(id: String, direction: Int) = editResult { SectionEngine.move(it, id, direction) }
    fun delete(id: String) = editResult { SectionEngine.delete(it, id) }
    fun addSection() = editResult { SectionEngine.addCustom(it) }

    fun regroup() {
        editResult { current ->
            EditResult.Ok(
                current.copy(
                    sections = SectionEngine.group(
                        current.cues,
                        gapMs = settings.gapThresholdMs,
                        maxSectionMs = settings.maxSectionMs,
                    ),
                ),
            )
        }
    }

    fun toggleSelected(id: String) {
        _ui.update { state ->
            val next = if (id in state.selected) state.selected - id else state.selected + id
            state.copy(selected = next)
        }
    }

    fun undo() {
        val previous = undo.undo(project) ?: return
        _ui.update { it.copy(project = previous, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        persistProject()
    }

    fun redo() {
        val next = undo.redo(project) ?: return
        _ui.update { it.copy(project = next, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        persistProject()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        _ui.update { it.copy(settings = next) }
        viewModelScope.launch(Dispatchers.IO) { stores.saveSettings(next) }
    }

    fun setTheme(mode: ThemeMode) = updateSettings { it.copy(theme = mode) }

    fun applyVideoPreset(preset: VideoPreset) {
        val abi = ffmpegAbiSupported(android.os.Build.SUPPORTED_ABIS.toList())
        updateSettings { it.copy(video = applyPreset(it.video, preset, abi)) }
        if (preset == VideoPreset.REFERENCE && !abi) {
            _ui.update {
                it.copy(banner = "libx264 is not in this device ABI. The reference profile will use the device H.264 encoder.")
            }
        }
    }

    fun applyPngResolution(preset: PngResolutionPreset) {
        updateSettings { it.copy(png = applyPngPreset(it.png, preset)) }
    }

    fun setPngFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags) }
        updateSettings { it.copy(pngFolderUri = uri.toString()) }
    }

    fun clearExportResult() { ExportBus.state.value = ExportUiState.Idle }

    fun startExport(kind: ExportKind, videoUri: String?, confirmedHeavy: Boolean) {
        if (kind != ExportKind.VIDEO && settings.pngFolderUri.isBlank()) {
            _ui.update { it.copy(banner = "Choose a folder for the section images first.") }
            return
        }
        val ids = if (kind == ExportKind.PNG_SELECTED) _ui.value.selected.toList() else emptyList()
        if (kind == ExportKind.PNG_SELECTED && ids.isEmpty()) {
            _ui.update { it.copy(banner = "Select at least one section.") }
            return
        }
        val request = ExportRequest(
            kind = kind,
            project = project,
            settings = settings,
            sectionIds = ids,
            videoDestinationUri = videoUri,
            pngTreeUri = settings.pngFolderUri.ifBlank { null },
            confirmedHeavy = confirmedHeavy,
        )
        ExportBus.pending = request
        ExportBus.state.value = ExportUiState.Running(ExportProgress("Starting", 0, 1, "", 0, 0, 0))
        val app = getApplication<Application>()
        val notificationsOk = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (notificationsOk) {
            ExportForegroundService.start(app)
        } else {
            _ui.update { it.copy(banner = "Notifications are off, so keep the app open until the export finishes.") }
            viewModelScope.launch(Dispatchers.Default) {
                val token = CancelToken()
                ExportBus.token = token
                ExportBus.state.value = ExportCoordinator(app).run(request, token) { progress ->
                    ExportBus.state.value = ExportUiState.Running(progress)
                }
                ExportBus.token = null
            }
        }
    }

    fun cancelExport() = ExportBus.cancel()

    fun share(uris: List<String>, mime: String) {
        val app = getApplication<Application>()
        val parsed = uris.map { Uri.parse(it) }
        val intent = if (parsed.size <= 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                if (parsed.isNotEmpty()) putExtra(Intent.EXTRA_STREAM, parsed.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(parsed))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(Intent.createChooser(intent, "Share export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyDiagnostics() {
        val text = _ui.value.diagnostics + "\n\n" + DiagLog.text()
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
        _ui.update { it.copy(banner = "Diagnostics copied") }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val encoders = runCatching { listAvcEncoders() }.getOrDefault(emptyList())
            val ffmpeg = runCatching { ffmpegVersionSafe() }.getOrDefault("unavailable")
            val libs = runCatching { ffmpegLibrariesSafe() }.getOrDefault(emptyList())
            val report = buildString {
                append("FFmpeg artifact: dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.9\n")
                append("FFmpeg runtime version: $ffmpeg\n")
                append("External libraries: ${libs.joinToString().ifBlank { "not loaded" }}\n")
                append("libx264 reported: ${libs.any { it.contains("x264", true) }}\n")
                append("Device ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n")
                append("H.264 encoders:\n")
                if (encoders.isEmpty()) append("  none reported\n")
                encoders.forEach { choice ->
                    append("  ${choice.name} (${if (choice.likelyHardware) "likely hardware" else "software"})\n")
                }
                append("\nTimeline export renders off-screen. It does not record the phone UI.\n")
                append("Device encoder path: MediaCodec video/avc + MediaMuxer.\n")
                append("Software path: FFmpeg libx264. Silent audio, when enabled, is AAC-LC via FFmpeg.\n")
                append("\nLog:\n")
                append(DiagLog.text())
            }
            _ui.update { it.copy(diagnostics = report) }
        }
    }

    fun cleanupTemps() {
        viewModelScope.launch(Dispatchers.IO) {
            val cache = java.io.File(getApplication<Application>().cacheDir, "exports")
            cache.listFiles()?.forEach { it.delete() }
            _ui.update { it.copy(banner = "Temporary export files removed") }
        }
    }

    private fun edit(transform: (Project) -> Project) {
        undo.snapshot(project)
        val next = transform(project)
        _ui.update { it.copy(project = next, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        persistProject()
    }

    private fun editResult(transform: (Project) -> EditResult) {
        when (val result = transform(project)) {
            is EditResult.Ok -> {
                undo.snapshot(project)
                _ui.update { it.copy(project = result.project, canUndo = undo.canUndo, canRedo = undo.canRedo) }
                persistProject()
            }
            is EditResult.Rejected -> _ui.update { it.copy(banner = result.reason) }
        }
    }

    private fun replaceProject(next: Project, remember: Boolean) {
        if (remember) undo.snapshot(project)
        _ui.update {
            it.copy(
                project = next,
                selected = emptySet(),
                canUndo = undo.canUndo,
                canRedo = undo.canRedo,
            )
        }
        persistProject()
    }

    private fun persistProject() {
        val snapshot = project
        viewModelScope.launch(Dispatchers.IO) { stores.saveProject(snapshot) }
    }
}
