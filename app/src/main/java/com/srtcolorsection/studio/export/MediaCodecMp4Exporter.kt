package com.srtcolorsection.studio.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.PowerManager
import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.ExportCancelled
import com.srtcolorsection.studio.domain.Project
import com.srtcolorsection.studio.domain.ThermalBand
import com.srtcolorsection.studio.domain.VideoSettings
import com.srtcolorsection.studio.domain.frameCountFor
import com.srtcolorsection.studio.domain.frameTimeMs
import com.srtcolorsection.studio.domain.thermalBand
import com.srtcolorsection.studio.render.FrameStyle
import com.srtcolorsection.studio.render.TimelineRenderer
import java.io.File

/**
 * Encodes the off-screen timeline with the device H.264 encoder (MediaCodec) and MediaMuxer.
 * This path does not claim libx264. Hardware vs software is reported from the codec name.
 * Audio, when requested, is added afterwards by FFmpeg as a real silent AAC track.
 */
class MediaCodecMp4Exporter(
    private val powerManager: PowerManager?,
    private val thermalWarnings: Boolean,
) {
    fun export(
        project: Project,
        video: VideoSettings,
        output: File,
        style: FrameStyle,
        cancel: CancelToken,
        onProgress: (ExportProgress) -> Unit,
        onNote: (String) -> Unit,
    ): AvcEncoderChoice {
        val choice = chooseAvcEncoder()
        if (!encoderSupportsSize(choice.name, video.width, video.height)) {
            error(
                "${choice.name} does not support ${video.width}×${video.height}. " +
                    "Use 1280×720 or the software libx264 encoder.",
            )
        }
        onNote(encoderLabel(choice))
        DiagLog.add(encoderLabel(choice))
        val frameCount = frameCountFor(project.exportDurationMs, video.fps)
        if (frameCount <= 0) error("The timeline duration is zero")
        val renderer = TimelineRenderer()
        val bitmap = renderer.createBitmap(video.width, video.height)
        val pixels = IntArray(video.width * video.height)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec = MediaCodec.createByCodecName(choice.name)
            val format = videoFormat(video, choice.colorFormat)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (error: Exception) {
                if (video.fps == 31) {
                    throw IllegalStateException(
                        "The device encoder ${choice.name} rejected 31 fps at " +
                            "${video.width}×${video.height}. It was not switched to 30 fps. " +
                            "Choose the software libx264 encoder or a 30 fps preset. ${error.message}",
                        error,
                    )
                }
                throw IllegalStateException(
                    "Encoder ${choice.name} failed to start: ${error.message}",
                    error,
                )
            }
            codec.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var track = -1
            var inputDone = false
            var outputDone = false
            var frameIndex = 0
            while (!outputDone) {
                cancel.check()
                maybeThermal(frameIndex, onNote)
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(15_000)
                    if (inIndex >= 0) {
                        if (frameIndex >= frameCount) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val timeMs = frameTimeMs(frameIndex, video.fps)
                            renderer.drawFrame(bitmap, project, timeMs, style)
                            bitmap.getPixels(pixels, 0, video.width, 0, 0, video.width, video.height)
                            val image = codec.getInputImage(inIndex)
                            if (image != null) {
                                writeArgbToYuvImage(image, pixels, video.width, video.height)
                                val pts = frameIndex * 1_000_000L / video.fps
                                codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                            } else {
                                val buffer = codec.getInputBuffer(inIndex)
                                    ?: error("Encoder ${choice.name} returned no input buffer")
                                val size = writePackedYuv(buffer, choice.colorFormat, pixels, video.width, video.height)
                                val pts = frameIndex * 1_000_000L / video.fps
                                codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            }
                            frameIndex += 1
                            val percent = (frameIndex * 100 / frameCount).coerceIn(0, 99)
                            onProgress(
                                ExportProgress(
                                    phase = "Encoding H.264",
                                    current = frameIndex,
                                    total = frameCount,
                                    filename = output.name,
                                    percent = percent,
                                    success = 0,
                                    failed = 0,
                                    detail = encoderLabel(choice),
                                ),
                            )
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 15_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) error("Encoder changed format twice")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        DiagLog.add("Muxer started ${codec.outputFormat}")
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && encoded != null) {
                            if (!muxerStarted) error("Received a sample before the muxer started")
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(track, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            muxer.stop()
            muxerStarted = false
        } catch (cancelled: ExportCancelled) {
            throw cancelled
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            bitmap.recycle()
        }
        return choice
    }

    private fun videoFormat(video: VideoSettings, colorFormat: Int): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, video.width, video.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, video.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, video.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= 24) {
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            runCatching {
                val caps = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .firstOrNull { it.name == chooseAvcEncoder().name }
                    ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                if (caps?.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    ) == true
                ) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                }
            }
        }
    }

    private fun maybeThermal(frame: Int, onNote: (String) -> Unit) {
        if (!thermalWarnings || powerManager == null || frame % 24 != 0) return
        if (Build.VERSION.SDK_INT < 29) return
        val band = thermalBand(powerManager.currentThermalStatus)
        when (band) {
            ThermalBand.CRITICAL -> throw IllegalStateException(
                "The device reported critical thermal status. Export stopped so it can cool down. " +
                    "Retry with the Mobile Safe preset. Android thermal limits cannot be overridden.",
            )
            ThermalBand.HOT -> {
                onNote("Device is hot. Pausing the encoder briefly.")
                DiagLog.add("Thermal status severe; pausing")
                Thread.sleep(1200)
            }
            ThermalBand.WARM, ThermalBand.NONE -> Unit
        }
    }
}

private fun writePackedYuv(buffer: java.nio.ByteBuffer, colorFormat: Int, argb: IntArray, width: Int, height: Int): Int {
    val ySize = width * height
    val cSize = ySize / 4
    val semi = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
        colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar ||
        colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    val needed = ySize + cSize * 2
    if (buffer.capacity() < needed) {
        error("Encoder input buffer is ${buffer.capacity()} bytes, need $needed for ${width}×${height}")
    }
    val out = ByteArray(needed)
    var yIndex = 0
    var uIndex = ySize
    var vIndex = ySize + cSize
    for (row in 0 until height) {
        for (col in 0 until width) {
            val pixel = argb[row * width + col]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            out[yIndex++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
            if (row % 2 == 0 && col % 2 == 0) {
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                if (semi) {
                    val uv = ySize + (row / 2) * width + col
                    if (uv + 1 < out.size) {
                        out[uv] = u
                        out[uv + 1] = v
                    }
                } else {
                    out[uIndex++] = u
                    out[vIndex++] = v
                }
            }
        }
    }
    buffer.clear()
    buffer.put(out)
    return needed
}
