package com.srtcolorsection.studio.domain

import kotlinx.serialization.Serializable

@Serializable
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class ParseWarning(
    val message: String,
    val line: Int = 0,
)

@Serializable
data class SrtParseResult(
    val cues: List<SubtitleCue>,
    val warnings: List<ParseWarning>,
)

@Serializable
data class TimelineSection(
    val id: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val colorArgb: Int,
    val cueIndices: List<Int> = emptyList(),
    val displayOrder: Int = 0,
    val notes: String = "",
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

@Serializable
data class Project(
    val name: String = "Untitled project",
    val cues: List<SubtitleCue> = emptyList(),
    val sections: List<TimelineSection> = emptyList(),
    val sourceLabel: String = "",
    val warnings: List<String> = emptyList(),
) {
    val timelineStartMs: Long
        get() = sections.minOfOrNull { it.startMs } ?: cues.minOfOrNull { it.startMs } ?: 0L

    val timelineEndMs: Long
        get() = sections.maxOfOrNull { it.endMs } ?: cues.maxOfOrNull { it.endMs } ?: 0L

    /** Export length is absolute, from 00:00:00.000 through the last section end. */
    val exportDurationMs: Long get() = timelineEndMs.coerceAtLeast(0L)

    val contentDurationMs: Long get() = (timelineEndMs - timelineStartMs).coerceAtLeast(0L)
}

sealed class EditResult {
    data class Ok(val project: Project) : EditResult()
    data class Rejected(val reason: String) : EditResult()
}

enum class VideoPreset {
    MOBILE_SAFE,
    BALANCED,
    FULL_HD,
    REFERENCE,
    CUSTOM,
}

enum class VideoEncoderChoice {
    /** Android MediaCodec video/avc. Hardware only when the device codec name says so. */
    DEVICE_MEDIACODEC,

    /** FFmpeg libx264 software encoder. Requires arm64-v8a or x86_64. */
    FFMPEG_LIBX264,
}

enum class FilenamePattern {
    NUMBER_NAME,
    NUMBER_TIMESTAMP,
    NUMBER_NAME_TIMESTAMP,
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class PngResolutionPreset { HD_720, FHD_1080, CUSTOM }

@Serializable
data class VideoSettings(
    val preset: VideoPreset = VideoPreset.MOBILE_SAFE,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 24,
    val videoBitrate: Int = 4_000_000,
    val encoder: VideoEncoderChoice = VideoEncoderChoice.DEVICE_MEDIACODEC,
    val audioEnabled: Boolean = false,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val fastStart: Boolean = true,
    val x264Preset: String = "veryfast",
    val threads: Int = 2,
)

@Serializable
data class PngSettings(
    val preset: PngResolutionPreset = PngResolutionPreset.FHD_1080,
    val width: Int = 1920,
    val height: Int = 1080,
    val transparent: Boolean = false,
    val showTimestamps: Boolean = true,
    val showSectionNumber: Boolean = true,
    val showSectionName: Boolean = true,
    val showDuration: Boolean = true,
    val showSubtitles: Boolean = true,
    val showTimeline: Boolean = true,
    val filenamePattern: FilenamePattern = FilenamePattern.NUMBER_NAME_TIMESTAMP,
    val zipBundle: Boolean = false,
    val backgroundArgb: Int = 0xFF12161C.toInt(),
    val textArgb: Int = 0xFFF4F7FB.toInt(),
)

@Serializable
data class PerformanceSettings(
    val safeMode: Boolean = true,
    val maxConcurrentRenders: Int = 1,
    val memoryConscious: Boolean = true,
    val cleanupTemp: Boolean = true,
    val lowStorageWarnings: Boolean = true,
    val thermalWarnings: Boolean = true,
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultProjectName: String = "Untitled project",
    val gapThresholdMs: Long = 1_200L,
    val maxSectionMs: Long = 30_000L,
    val cleanupTempOnFinish: Boolean = true,
    val video: VideoSettings = VideoSettings(),
    val png: PngSettings = PngSettings(),
    val performance: PerformanceSettings = PerformanceSettings(),
    val pngFolderUri: String = "",
)

object Palette {
    val colors: List<Int> = listOf(
        0xFFE85D4C.toInt(),
        0xFFF4A261.toInt(),
        0xFF2A9D8F.toInt(),
        0xFF4C6EF5.toInt(),
        0xFF9B5DE5.toInt(),
        0xFF43AA8B.toInt(),
        0xFFE9C46A.toInt(),
        0xFFFF6B6B.toInt(),
    )

    fun colorFor(index: Int): Int = colors[index.floorMod(colors.size)]

    fun next(used: Collection<Int>): Int =
        colors.firstOrNull { it !in used } ?: colors[used.size.floorMod(colors.size)]
}

private fun Int.floorMod(other: Int): Int {
    val m = this % other
    return if (m < 0) m + other else m
}

fun applyPreset(current: VideoSettings, preset: VideoPreset, ffmpegAbiAvailable: Boolean): VideoSettings {
    return when (preset) {
        VideoPreset.MOBILE_SAFE -> current.copy(
            preset = preset,
            width = 1280,
            height = 720,
            fps = 24,
            videoBitrate = 4_000_000,
            encoder = VideoEncoderChoice.DEVICE_MEDIACODEC,
            audioEnabled = false,
            threads = 2,
            x264Preset = "veryfast",
        )
        VideoPreset.BALANCED -> current.copy(
            preset = preset,
            width = 1280,
            height = 720,
            fps = 30,
            videoBitrate = 6_000_000,
            encoder = VideoEncoderChoice.DEVICE_MEDIACODEC,
            audioEnabled = false,
            threads = 2,
            x264Preset = "veryfast",
        )
        VideoPreset.FULL_HD -> current.copy(
            preset = preset,
            width = 1920,
            height = 1080,
            fps = 30,
            videoBitrate = 8_000_000,
            encoder = VideoEncoderChoice.DEVICE_MEDIACODEC,
            audioEnabled = false,
            threads = 2,
            x264Preset = "veryfast",
        )
        VideoPreset.REFERENCE -> current.copy(
            preset = preset,
            width = 1920,
            height = 1080,
            fps = 31,
            videoBitrate = 11_900_000,
            encoder = if (ffmpegAbiAvailable) {
                VideoEncoderChoice.FFMPEG_LIBX264
            } else {
                VideoEncoderChoice.DEVICE_MEDIACODEC
            },
            audioEnabled = true,
            audioBitrate = 128_000,
            sampleRate = 44_100,
            channels = 2,
            fastStart = true,
            threads = 2,
            x264Preset = "veryfast",
        )
        VideoPreset.CUSTOM -> current.copy(preset = VideoPreset.CUSTOM)
    }
}

fun applyPngPreset(current: PngSettings, preset: PngResolutionPreset): PngSettings = when (preset) {
    PngResolutionPreset.HD_720 -> current.copy(preset = preset, width = 1280, height = 720)
    PngResolutionPreset.FHD_1080 -> current.copy(preset = preset, width = 1920, height = 1080)
    PngResolutionPreset.CUSTOM -> current.copy(preset = preset)
}
