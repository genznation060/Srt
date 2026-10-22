package com.srtcolorsection.studio.export

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.content.FileProvider
import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.ExportCancelled
import com.srtcolorsection.studio.domain.ExpectedMedia
import com.srtcolorsection.studio.domain.VideoEncoderChoice
import com.srtcolorsection.studio.domain.assessBitmapMemory
import com.srtcolorsection.studio.domain.assessLength
import com.srtcolorsection.studio.domain.buildRemuxArguments
import com.srtcolorsection.studio.domain.evenDimension
import com.srtcolorsection.studio.domain.ffmpegAbiSupported
import com.srtcolorsection.studio.domain.planStorage
import com.srtcolorsection.studio.domain.summarizeProbe
import com.srtcolorsection.studio.domain.validateMedia
import java.io.File

class ExportCoordinator(private val context: Context) {
    suspend fun run(
        request: ExportRequest,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
    ): ExportUiState {
        return try {
            when (request.kind) {
                ExportKind.VIDEO -> exportVideo(request, cancel, onProgress)
                ExportKind.PNG_ALL -> exportPng(request, null, cancel, onProgress)
                ExportKind.PNG_SELECTED -> exportPng(request, request.sectionIds, cancel, onProgress)
            }
        } catch (cancelled: ExportCancelled) {
            DiagLog.add("Export cancelled")
            ExportUiState.Cancelled("Export cancelled. The project was not changed.")
        }
    }

    private suspend fun exportVideo(
        request: ExportRequest,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
    ): ExportUiState {
        val project = request.project
        if (project.sections.isEmpty()) {
            return ExportUiState.Failed("Import subtitles and create at least one section first.", "")
        }
        val video = request.settings.video.copy(
            width = evenDimension(request.settings.video.width),
            height = evenDimension(request.settings.video.height),
            fps = request.settings.video.fps.coerceIn(1, 60),
            channels = 2,
            sampleRate = 44_100,
            threads = request.settings.video.threads.coerceIn(1, 4),
        )
        val heap = Runtime.getRuntime().maxMemory()
        val memory = assessBitmapMemory(heap, video.width, video.height)
        if (!memory.allowed) return ExportUiState.Failed(memory.warning ?: "Resolution is not safe", "")
        val length = assessLength(project.exportDurationMs, video.fps)
        if (length.warning != null && !request.confirmedHeavy) {
            return ExportUiState.Failed(length.warning + " Confirm the export to continue.", length.warning)
        }
        if (memory.warning != null && !request.confirmedHeavy && video.width * video.height > 1920 * 1080) {
            return ExportUiState.Failed(memory.warning, memory.warning)
        }
        val available = availableBytes(context.cacheDir)
        val storage = planStorage(available, video.width, video.height, 0, true, video.videoBitrate, project.exportDurationMs)
        if (!storage.ok && request.settings.performance.lowStorageWarnings) {
            return ExportUiState.Failed(storage.message, storage.message)
        }
        if (video.encoder == VideoEncoderChoice.FFMPEG_LIBX264 &&
            !ffmpegAbiSupported(android.os.Build.SUPPORTED_ABIS.toList())
        ) {
            return ExportUiState.Failed(
                "Software libx264 is not packaged for this device ABI. Switch to the device H.264 encoder.",
                android.os.Build.SUPPORTED_ABIS.joinToString(),
            )
        }
        val cache = File(context.cacheDir, "exports").apply { mkdirs() }
        val raw = File(cache, "timeline-${System.currentTimeMillis()}.mp4")
        val finalFile = File(cache, "timeline-final-${System.currentTimeMillis()}.mp4")
        ExportRecovery.begin(context, listOf(raw, finalFile))
        val notes = StringBuilder()
        try {
            val style = videoFrameStyle(project, request.settings.png.backgroundArgb, request.settings.png.textArgb)
            when (video.encoder) {
                VideoEncoderChoice.DEVICE_MEDIACODEC -> {
                    val choice = MediaCodecMp4Exporter(
                        context.getSystemService(PowerManagerOrNull()),
                        request.settings.performance.thermalWarnings,
                    ).export(project, video, raw, style, cancel, onProgress) { notes.append(it).append('\n') }
                    notes.append(encoderLabel(choice)).append('\n')
                    if (video.audioEnabled || video.fastStart) {
                        remux(raw, finalFile, video, project.exportDurationMs, cancel, notes)
                    }
                }
                VideoEncoderChoice.FFMPEG_LIBX264 -> {
                    Libx264Mp4Exporter(context).export(
                        project, video, raw, style, cancel, onProgress,
                    ) { notes.append(it).append('\n') }
                }
            }
            cancel.check()
            val encoded = when {
                finalFile.exists() && finalFile.length() > 0L -> finalFile
                raw.exists() && raw.length() > 0L -> raw
                else -> return ExportUiState.Failed("Encoder did not produce a file.", notes.toString())
            }
            onProgress(ExportProgress("Validating", 1, 1, encoded.name, 99, 0, 0, "Checking the MP4"))
            val probe = probeMediaFile(encoded)
            val issues = validateMedia(
                probe,
                ExpectedMedia(
                    width = video.width,
                    height = video.height,
                    fps = video.fps,
                    wantAudio = video.audioEnabled,
                    durationUs = project.exportDurationMs * 1000L,
                    videoBitrate = video.videoBitrate,
                ),
            )
            val report = buildString {
                append(notes)
                append(summarizeProbe(probe))
                append("\nTarget bitrate ${video.videoBitrate / 1000} kbps")
                append("\nEncoder choice ${video.encoder}")
                if (video.encoder == VideoEncoderChoice.FFMPEG_LIBX264) {
                    append("\nMuxer/encoder: FFmpeg ${ffmpegVersionSafe()} libx264 (software)")
                }
                if (issues.isNotEmpty()) {
                    append("\n\nValidation:\n")
                    issues.forEach { append("• ").append(it.message).append('\n') }
                }
            }
            DiagLog.add(report)
            if (issues.any { it.blocking }) {
                encoded.delete()
                return ExportUiState.Failed(
                    issues.joinToString(" ") { it.message },
                    report,
                )
            }
            val saved = publishVideo(encoded, request.videoDestinationUri, project.name)
            if (request.settings.performance.cleanupTemp) {
                raw.delete()
                finalFile.delete()
            }
            ExportRecovery.finish(context)
            return ExportUiState.Succeeded(
                summary = "Video saved. ${encoded.length() / 1024} KB before copy.",
                diagnostic = report,
                shareUris = listOf(saved),
                mime = "video/mp4",
            )
        } catch (cancelled: ExportCancelled) {
            raw.delete()
            finalFile.delete()
            ExportRecovery.finish(context)
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            raw.delete()
            finalFile.delete()
            ExportRecovery.finish(context)
            return ExportUiState.Failed(
                "The device ran out of memory. Use the Mobile Safe 720p preset.",
                oom.message ?: "",
            )
        } catch (error: Exception) {
            raw.delete()
            finalFile.delete()
            ExportRecovery.finish(context)
            DiagLog.add("Video export failed: ${error.stackTraceToString().take(2000)}")
            return ExportUiState.Failed(error.message ?: "Video export failed", error.stackTraceToString().take(2500))
        }
    }

    private suspend fun remux(
        input: File,
        output: File,
        video: com.srtcolorsection.studio.domain.VideoSettings,
        durationMs: Long,
        cancel: CancelToken,
        notes: StringBuilder,
    ) {
        if (!ffmpegAbiSupported(android.os.Build.SUPPORTED_ABIS.toList())) {
            if (video.audioEnabled) {
                error("A silent AAC track needs FFmpeg, which is not available on this ABI.")
            }
            notes.append("Faststart skipped: FFmpeg ABI is not available.\n")
            return
        }
        val args = buildRemuxArguments(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            durationMs = durationMs,
            audioEnabled = video.audioEnabled,
            audioBitrate = video.audioBitrate,
            sampleRate = video.sampleRate,
            channels = video.channels,
            fastStart = video.fastStart,
        )
        DiagLog.add("Remux ${args.joinToString(" ")}")
        val result = FfmpegRunner.execute(args, cancel) { DiagLog.add(it) }
        if (cancel.isCancelled() || result.cancelled) throw ExportCancelled()
        if (!result.success) {
            if (video.audioEnabled) {
                error("Could not encode the silent AAC track. ${result.log.take(500)}")
            }
            notes.append("Faststart remux failed; the video file was kept without it. ${result.log.take(300)}\n")
            output.delete()
        } else {
            notes.append(
                if (video.audioEnabled) "Added a silent AAC-LC stereo track with FFmpeg ${ffmpegVersionSafe()}.\n"
                else "Remuxed with +faststart using FFmpeg ${ffmpegVersionSafe()}.\n",
            )
        }
    }

    private fun publishVideo(file: File, destination: String?, projectName: String): String {
        if (!destination.isNullOrBlank()) {
            val uri = Uri.parse(destination)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Could not write the video to the selected location")
            val written = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
            if (written != null && written <= 0L) error("The destination video is empty")
            return destination
        }
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val safe = projectName.replace(Regex("""[^\p{L}\p{N}._-]+"""), "_").ifBlank { "timeline" }
        val target = File(dir, "${safe}_${System.currentTimeMillis()}.mp4")
        file.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString()
    }

    private fun exportPng(
        request: ExportRequest,
        sectionIds: List<String>?,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
    ): ExportUiState {
        val tree = request.pngTreeUri ?: return ExportUiState.Failed(
            "Choose a destination folder for the PNG images.",
            "",
        )
        val png = request.settings.png.copy(
            width = evenDimension(request.settings.png.width),
            height = evenDimension(request.settings.png.height),
        )
        val memory = assessBitmapMemory(Runtime.getRuntime().maxMemory(), png.width, png.height)
        if (!memory.allowed) return ExportUiState.Failed(memory.warning ?: "PNG resolution is not safe", "")
        val count = sectionIds?.size ?: request.project.sections.size
        val storage = planStorage(
            availableBytes(context.cacheDir),
            png.width,
            png.height,
            count,
            false,
            0,
            0,
        )
        if (!storage.ok && request.settings.performance.lowStorageWarnings) {
            return ExportUiState.Failed(storage.message, "")
        }
        val adjusted = request.copy(settings = request.settings.copy(png = png))
        return try {
            val result = PngBatchExporter(context).export(
                project = adjusted.project,
                settings = png,
                sectionIds = sectionIds,
                treeUri = Uri.parse(tree),
                cancel = cancel,
                onProgress = onProgress,
            )
            val summary = buildString {
                append("${result.exported} PNG image")
                if (result.exported != 1) append("s")
                append(" exported")
                if (result.failed > 0) append(", ${result.failed} failed")
                append(".")
                if (result.zipUri != null) append(" A ZIP bundle was written as well.")
            }
            val diagnostic = result.failures.joinToString("\n")
            if (result.exported == 0) {
                ExportUiState.Failed(summary + " " + diagnostic, diagnostic)
            } else {
                ExportUiState.Succeeded(
                    summary = summary,
                    diagnostic = diagnostic,
                    shareUris = result.uris + listOfNotNull(result.zipUri),
                    mime = "image/png",
                )
            }
        } catch (cancelled: ExportCancelled) {
            throw cancelled
        } catch (error: Exception) {
            DiagLog.add("PNG export failed: ${error.message}")
            ExportUiState.Failed(error.message ?: "PNG export failed", error.stackTraceToString().take(2000))
        }
    }
}

private fun availableBytes(dir: File): Long {
    val stat = StatFs(dir.absolutePath)
    return stat.availableBytes
}

private fun PowerManagerOrNull(): Class<android.os.PowerManager> = android.os.PowerManager::class.java

object ExportRecovery {
    private fun marker(context: Context) = File(context.filesDir, "export-in-progress.txt")
    private fun note(context: Context) = File(context.filesDir, "export-interrupted.txt")

    fun begin(context: Context, files: List<File>) {
        marker(context).writeText(files.joinToString("\n") { it.absolutePath })
    }

    fun finish(context: Context) {
        marker(context).delete()
    }

    fun cleanup(context: Context): String? {
        val file = marker(context)
        if (!file.exists()) return null
        file.readLines().forEach { path ->
            runCatching { File(path).delete() }
        }
        file.delete()
        val message = "An earlier export was interrupted. Incomplete files were deleted. The project is unchanged."
        note(context).writeText(message)
        return message
    }

    fun consumeNote(context: Context): String? {
        val file = note(context)
        if (!file.exists()) return null
        val text = file.readText()
        file.delete()
        return text
    }
}
