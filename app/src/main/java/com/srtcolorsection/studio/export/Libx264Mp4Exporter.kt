package com.srtcolorsection.studio.export

import android.content.Context
import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.ExportCancelled
import com.srtcolorsection.studio.domain.Project
import com.srtcolorsection.studio.domain.VideoSettings
import com.srtcolorsection.studio.domain.buildLibx264Arguments
import com.srtcolorsection.studio.domain.ffmpegAbiSupported
import com.srtcolorsection.studio.domain.frameCountFor
import com.srtcolorsection.studio.domain.frameTimeMs
import com.srtcolorsection.studio.render.FrameStyle
import com.srtcolorsection.studio.render.TimelineRenderer
import java.io.File
import java.io.OutputStream

/**
 * Software H.264 encode through FFmpeg libx264. Frames are rendered one at a time
 * and written to an FFmpeg pipe. They are not stored as a list of bitmaps or PNG files.
 */
class Libx264Mp4Exporter(private val context: Context) {
    suspend fun export(
        project: Project,
        video: VideoSettings,
        output: File,
        style: FrameStyle,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
        onNote: (String) -> Unit,
    ) {
        val abis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()
        if (!ffmpegAbiSupported(abis)) {
            error(
                "libx264 is packaged for arm64-v8a and x86_64 only. This device reports ${abis.joinToString()}. " +
                    "Use the device H.264 encoder instead.",
            )
        }
        val libs = ffmpegLibrariesSafe()
        if (libs.none { it.contains("x264", ignoreCase = true) }) {
            error(
                "This FFmpeg build did not report libx264 (external libraries: ${libs.joinToString()}). " +
                    "Software H.264 export was not started.",
            )
        }
        onNote("Software encoder libx264 via FFmpeg ${ffmpegVersionSafe()}")
        DiagLog.add("libx264 encode ${video.width}x${video.height} @ ${video.fps} fps")
        val frameCount = frameCountFor(project.exportDurationMs, video.fps)
        if (frameCount <= 0) error("The timeline duration is zero")
        val logs = StringBuilder()
        val result = FfmpegRunner.withPipe(
            context = context,
            cancel = cancel,
            arguments = { pipe ->
                buildLibx264Arguments(
                    pipePath = pipe,
                    outputPath = output.absolutePath,
                    width = video.width,
                    height = video.height,
                    fps = video.fps,
                    videoBitrate = video.videoBitrate,
                    durationMs = project.exportDurationMs,
                    audioEnabled = video.audioEnabled,
                    audioBitrate = video.audioBitrate,
                    sampleRate = video.sampleRate,
                    channels = video.channels,
                    fastStart = video.fastStart,
                    x264Preset = video.x264Preset,
                    threads = video.threads,
                )
            },
            write = { stream ->
                writeFrames(project, video, style, frameCount, stream, cancel, onProgress)
            },
            onLog = { line ->
                if (logs.length < 12_000) logs.append(line).append('\n')
            },
        )
        DiagLog.add("libx264 finished success=${result.success} code=${result.code}")
        if (result.cancelled || cancel.isCancelled()) throw ExportCancelled()
        if (!result.success || !output.exists() || output.length() <= 0L) {
            error("libx264 export failed (code ${result.code}). ${result.log.ifBlank { logs.toString() }}".take(2000))
        }
    }

    private fun writeFrames(
        project: Project,
        video: VideoSettings,
        style: FrameStyle,
        frameCount: Int,
        stream: OutputStream,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val renderer = TimelineRenderer()
        val bitmap = renderer.createBitmap(video.width, video.height)
        val pixels = IntArray(video.width * video.height)
        val row = ByteArray(video.width * 4)
        try {
            for (frame in 0 until frameCount) {
                cancel.check()
                renderer.drawFrame(bitmap, project, frameTimeMs(frame, video.fps), style)
                bitmap.getPixels(pixels, 0, video.width, 0, 0, video.width, video.height)
                for (y in 0 until video.height) {
                    var offset = 0
                    val base = y * video.width
                    for (x in 0 until video.width) {
                        val pixel = pixels[base + x]
                        row[offset++] = ((pixel ushr 16) and 0xFF).toByte()
                        row[offset++] = ((pixel ushr 8) and 0xFF).toByte()
                        row[offset++] = (pixel and 0xFF).toByte()
                        row[offset++] = ((pixel ushr 24) and 0xFF).toByte()
                    }
                    stream.write(row)
                }
                if (frame % 3 == 0 || frame == frameCount - 1) {
                    onProgress(
                        ExportProgress(
                            phase = "Encoding libx264",
                            current = frame + 1,
                            total = frameCount,
                            filename = "timeline.mp4",
                            percent = ((frame + 1) * 100 / frameCount).coerceIn(0, 99),
                            success = 0,
                            failed = 0,
                            detail = "FFmpeg ${ffmpegVersionSafe()} libx264",
                        ),
                    )
                }
            }
            stream.flush()
        } finally {
            bitmap.recycle()
        }
    }
}
