package com.srtcolorsection.studio.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

data class AvcEncoderChoice(
    val name: String,
    val likelyHardware: Boolean,
    val colorFormat: Int,
)

fun softwareEncoderName(name: String): Boolean {
    val normalized = name.lowercase()
    return normalized.contains("omx.google") ||
        normalized.contains("c2.android") ||
        normalized.contains("software") ||
        normalized.contains("sw.encoder")
}

fun listAvcEncoders(): List<AvcEncoderChoice> {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return list.codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        .map { info ->
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            AvcEncoderChoice(
                name = info.name,
                likelyHardware = !softwareEncoderName(info.name),
                colorFormat = pickColorFormat(caps),
            )
        }
}

fun chooseAvcEncoder(): AvcEncoderChoice {
    val all = listAvcEncoders()
    if (all.isEmpty()) error("This device does not expose an H.264 (video/avc) encoder")
    return all.firstOrNull { it.likelyHardware } ?: all.first()
}

fun pickColorFormat(caps: MediaCodecInfo.CodecCapabilities): Int {
    val have = caps.colorFormats.toSet()
    val preferred = intArrayOf(
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
    )
    return preferred.firstOrNull { it in have } ?: caps.colorFormats.first()
}

fun encoderSupportsSize(name: String, width: Int, height: Int): Boolean {
    return try {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        caps.videoCapabilities?.isSizeSupported(width, height) ?: true
    } catch (_: Exception) {
        true
    }
}

fun encoderLabel(choice: AvcEncoderChoice): String =
    if (choice.likelyHardware) {
        "Device H.264 encoder (likely hardware): ${choice.name}"
    } else {
        "Android software H.264 encoder: ${choice.name}"
    }

fun sdkInt(): Int = Build.VERSION.SDK_INT
