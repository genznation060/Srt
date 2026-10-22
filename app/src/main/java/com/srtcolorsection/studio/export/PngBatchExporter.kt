package com.srtcolorsection.studio.export

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.ExportCancelled
import com.srtcolorsection.studio.domain.FilenamePattern
import com.srtcolorsection.studio.domain.PngEncoder
import com.srtcolorsection.studio.domain.PngSettings
import com.srtcolorsection.studio.domain.Project
import com.srtcolorsection.studio.domain.SectionEngine
import com.srtcolorsection.studio.domain.sectionPngFileName
import com.srtcolorsection.studio.domain.zipBundleName
import com.srtcolorsection.studio.render.FrameStyle
import com.srtcolorsection.studio.render.TimelineRenderer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PngBatchResult(
    val exported: Int,
    val failed: Int,
    val uris: List<String>,
    val failures: List<String>,
    val zipUri: String?,
)

class PngBatchExporter(private val context: Context) {
    fun export(
        project: Project,
        settings: PngSettings,
        sectionIds: List<String>?,
        treeUri: Uri,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
    ): PngBatchResult {
        val directory = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("The selected folder is not available")
        if (!directory.canWrite()) error("The selected folder is not writable")
        val numbered = SectionEngine.numbered(project.sections)
        val chosen = if (sectionIds.isNullOrEmpty()) {
            numbered
        } else {
            numbered.filter { it.second.id in sectionIds }
        }
        if (chosen.isEmpty()) error("There are no sections to export")
        val taken = directory.listFiles().mapNotNull { it.name }.toMutableSet()
        val style = settings.toFrameStyle(project.name)
        val renderer = TimelineRenderer()
        val bitmap = renderer.createBitmap(settings.width, settings.height)
        val pixels = IntArray(settings.width * settings.height)
        val uris = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var success = 0
        var zipStream: ZipOutputStream? = null
        var zipDocument: DocumentFile? = null
        try {
            if (settings.zipBundle) {
                val zipName = zipBundleName(project.name, taken)
                taken += zipName
                zipDocument = directory.createFile("application/zip", zipName)
                    ?: error("Could not create $zipName in the selected folder")
                val raw = context.contentResolver.openOutputStream(zipDocument.uri)
                    ?: error("Could not open $zipName")
                zipStream = ZipOutputStream(raw)
            }
            chosen.forEachIndexed { index, (number, section) ->
                cancel.check()
                val filename = sectionPngFileName(section, number, settings.filenamePattern, taken)
                taken += filename
                onProgress(
                    ExportProgress(
                        phase = "Writing PNG",
                        current = index + 1,
                        total = chosen.size,
                        filename = filename,
                        percent = (index * 100 / chosen.size).coerceIn(0, 99),
                        success = success,
                        failed = failures.size,
                    ),
                )
                var created: DocumentFile? = null
                try {
                    renderer.drawSection(bitmap, project, section, number, style)
                    bitmap.getPixels(pixels, 0, settings.width, 0, 0, settings.width, settings.height)
                    val png = ByteArrayOutputStream()
                    PngEncoder.encodeTo(png, settings.width, settings.height, pixels)
                    val bytes = png.toByteArray()
                    verifyPng(bytes, settings.transparent, settings.width, settings.height)
                    created = directory.createFile("image/png", filename)
                        ?: error("Could not create $filename")
                    context.contentResolver.openOutputStream(created.uri)?.use { it.write(bytes) }
                        ?: error("Could not write $filename")
                    verifyStored(created.uri, settings.transparent)
                    zipStream?.let { zip ->
                        zip.putNextEntry(ZipEntry(filename))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    uris += created.uri.toString()
                    success += 1
                } catch (cancelled: ExportCancelled) {
                    created?.delete()
                    throw cancelled
                } catch (oom: OutOfMemoryError) {
                    created?.delete()
                    throw IllegalStateException(
                        "Ran out of memory while encoding $filename. Lower the PNG resolution.",
                        oom,
                    )
                } catch (error: Exception) {
                    created?.delete()
                    failures += "$filename: ${error.message}"
                    DiagLog.add("PNG failed $filename: ${error.message}")
                }
            }
        } catch (cancelled: ExportCancelled) {
            runCatching { zipStream?.close() }
            zipDocument?.delete()
            throw cancelled
        } finally {
            runCatching { zipStream?.close() }
            bitmap.recycle()
        }
        return PngBatchResult(
            exported = success,
            failed = failures.size,
            uris = uris,
            failures = failures,
            zipUri = zipDocument?.uri?.toString(),
        )
    }

    private fun verifyPng(bytes: ByteArray, transparent: Boolean, width: Int, height: Int) {
        if (!PngEncoder.isPng(bytes)) error("Encoder did not produce a PNG signature")
        if (PngEncoder.colorType(bytes) != 6) error("PNG is not RGBA (color type ${PngEncoder.colorType(bytes)})")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Android could not decode the PNG")
        try {
            if (decoded.width != width || decoded.height != height) error("Decoded PNG size does not match")
            if (transparent) {
                val alpha = decoded.getPixel(width - 2, 2) ushr 24
                if (alpha != 0) error("Transparent PNG lost its alpha channel (corner alpha=$alpha)")
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun verifyStored(uri: Uri, transparent: Boolean) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(8)
            val read = input.read(header)
            if (read < 8 || !PngEncoder.isPng(header.copyOf(8) + byteArrayOf(0))) {
                // isPng only checks the first 8 bytes; extra zero does not matter if we slice.
            }
            if (read < 8 || header[0] != 0x89.toByte() || header[1] != 0x50.toByte()) {
                error("Saved file is not a PNG")
            }
        } ?: error("Saved PNG could not be read back")
        if (transparent) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val decoded = BitmapFactory.decodeStream(input) ?: error("Saved PNG could not be decoded")
                val alpha = decoded.getPixel(decoded.width - 2, 2) ushr 24
                decoded.recycle()
                if (alpha != 0) error("Stored PNG does not keep transparency")
            }
        }
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray = this

fun PngSettings.toFrameStyle(projectName: String) = FrameStyle(
    backgroundArgb = backgroundArgb,
    textArgb = textArgb,
    transparent = transparent,
    showTimestamps = showTimestamps,
    showSectionNumber = showSectionNumber,
    showSectionName = showSectionName,
    showDuration = showDuration,
    showSubtitles = showSubtitles,
    showTimeline = showTimeline,
    projectName = projectName,
)

fun videoFrameStyle(project: Project, background: Int, text: Int) = FrameStyle(
    backgroundArgb = background,
    textArgb = text,
    transparent = false,
    showTimestamps = true,
    showSectionNumber = true,
    showSectionName = true,
    showDuration = false,
    showSubtitles = true,
    showTimeline = true,
    projectName = project.name,
)

@Suppress("unused")
private val filenamePatternUsed: FilenamePattern? = null
