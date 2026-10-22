package com.srtcolorsection.studio

import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.ExpectedMedia
import com.srtcolorsection.studio.domain.ExportCancelled
import com.srtcolorsection.studio.domain.FilenamePattern
import com.srtcolorsection.studio.domain.PngEncoder
import com.srtcolorsection.studio.domain.ProbedMedia
import com.srtcolorsection.studio.domain.ProbedStream
import com.srtcolorsection.studio.domain.Project
import com.srtcolorsection.studio.domain.SectionEngine
import com.srtcolorsection.studio.domain.SrtParser
import com.srtcolorsection.studio.domain.SubtitleCue
import com.srtcolorsection.studio.domain.TempFileTracker
import com.srtcolorsection.studio.domain.TimelineSection
import com.srtcolorsection.studio.domain.UndoStack
import com.srtcolorsection.studio.domain.VideoEncoderChoice
import com.srtcolorsection.studio.domain.VideoPreset
import com.srtcolorsection.studio.domain.VideoSettings
import com.srtcolorsection.studio.domain.applyPreset
import com.srtcolorsection.studio.domain.assessBitmapMemory
import com.srtcolorsection.studio.domain.assessLength
import com.srtcolorsection.studio.domain.buildLibx264Arguments
import com.srtcolorsection.studio.domain.buildRemuxArguments
import com.srtcolorsection.studio.domain.ffmpegAbiSupported
import com.srtcolorsection.studio.domain.formatClock
import com.srtcolorsection.studio.domain.formatFileStamp
import com.srtcolorsection.studio.domain.frameCountFor
import com.srtcolorsection.studio.domain.parseClock
import com.srtcolorsection.studio.domain.planStorage
import com.srtcolorsection.studio.domain.sanitizeFileToken
import com.srtcolorsection.studio.domain.sectionPngFileName
import com.srtcolorsection.studio.domain.thermalBand
import com.srtcolorsection.studio.domain.ThermalBand
import com.srtcolorsection.studio.domain.validateMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class DomainTests {
    @Test
    fun parsesBomCrlfMultilineAndUnicode() {
        val srt = "\uFEFF1\r\n00:00:00,000 --> 00:00:01,500\r\nHello world\r\n\r\n" +
            "2\r\n00:00:01,500 --> 00:00:03,000\r\nবাংলা সাবটাইটেল\r\nদ্বিতীয় লাইন\r\n\r\n" +
            "3\n00:00:03,200 --> 00:00:04,000\nहिन्दी उपशीर्षक\n"
        val result = SrtParser.parse(srt)
        assertEquals(3, result.cues.size)
        assertEquals("Hello world", result.cues[0].text)
        assertEquals("বাংলা সাবটাইটেল\nদ্বিতীয় লাইন", result.cues[1].text)
        assertEquals("हिन्दी उपशीर्षक", result.cues[2].text)
        assertEquals(0L, result.cues[0].startMs)
        assertEquals(1500L, result.cues[0].endMs)
        assertEquals(3200L, result.cues[2].startMs)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun skipsNegativeDurationAndMalformedBlocks() {
        val srt = """
            1
            00:00:05,000 --> 00:00:04,000
            too early

            not a cue

            2
            00:00:06,000 --> 00:00:07,250
            kept
        """.trimIndent()
        val result = SrtParser.parse(srt)
        assertEquals(1, result.cues.size)
        assertEquals("kept", result.cues[0].text)
        assertEquals(1250L, result.cues[0].endMs - result.cues[0].startMs)
        assertTrue(result.warnings.any { it.message.contains("invalid duration") })
    }

    @Test
    fun reportsOverlapsWithoutDroppingCues() {
        val srt = """
            1
            00:00:00,000 --> 00:00:02,000
            A

            2
            00:00:01,000 --> 00:00:03,000
            B
        """.trimIndent()
        val result = SrtParser.parse(srt)
        assertEquals(2, result.cues.size)
        assertTrue(result.warnings.any { it.message.contains("Overlapping") })
    }

    @Test
    fun groupsSplitsAndMerges() {
        val cues = listOf(
            SubtitleCue(1, 0, 1000, "a"),
            SubtitleCue(2, 1200, 2000, "b"),
            SubtitleCue(3, 5000, 7000, "c"),
        )
        var id = 0
        val sections = SectionEngine.group(cues, gapMs = 500, idFactory = { "id-${id++}" })
        assertEquals(2, sections.size)
        assertEquals(0L, sections[0].startMs)
        assertEquals(2000L, sections[0].endMs)
        val project = Project("p", cues, sections)
        val split = SectionEngine.split(project, sections[0].id, 1000, idFactory = { "split" })
        assertTrue(split is com.srtcolorsection.studio.domain.EditResult.Ok)
        val splitProject = (split as com.srtcolorsection.studio.domain.EditResult.Ok).project
        assertEquals(3, splitProject.sections.size)
        val rejected = SectionEngine.split(splitProject, "split", 1000)
        assertTrue(rejected is com.srtcolorsection.studio.domain.EditResult.Rejected)
        val first = splitProject.sections.minBy { it.startMs }
        val merged = SectionEngine.mergeWithNext(splitProject, first.id)
        assertTrue(merged is com.srtcolorsection.studio.domain.EditResult.Ok)
        val badTimes = SectionEngine.updateTimes(project, sections[0].id, 6000, 8000)
        assertTrue(badTimes is com.srtcolorsection.studio.domain.EditResult.Rejected)
        val negative = SectionEngine.updateTimes(project, sections[0].id, 1000, 500)
        assertTrue((negative as com.srtcolorsection.studio.domain.EditResult.Rejected).reason.contains("after"))
    }

    @Test
    fun filenamesAlwaysContainTimestampsAndStayUnique() {
        val section = TimelineSection("1", "Intro/duction!", 0, 3500, 0, displayOrder = 0)
        val name = sectionPngFileName(section, 1, FilenamePattern.NUMBER_NAME_TIMESTAMP, emptySet())
        assertEquals("section_001_Intro_duction_00-00-00-000_to_00-00-03-500.png", name)
        assertTrue(name.contains("00-00-00-000_to_00-00-03-500"))
        val timeOnly = sectionPngFileName(section, 2, FilenamePattern.NUMBER_TIMESTAMP, emptySet())
        assertTrue(timeOnly.contains("00-00-00-000_to_00-00-03-500"))
        assertFalse(timeOnly.contains("Intro"))
        val taken = setOf(name)
        val unique = sectionPngFileName(section, 1, FilenamePattern.NUMBER_NAME, taken)
        assertTrue(unique.contains("_2.png"))
        assertEquals("Bengali", sanitizeFileToken("বাংলা"))
        assertEquals("section", sanitizeFileToken("///"))
    }

    @Test
    fun clocksRoundTrip() {
        assertEquals("00:00:03.500", formatClock(3500))
        assertEquals("01:02:03.004", formatClock(3_723_004))
        assertEquals("00-00-03-500", formatFileStamp(3500))
        assertEquals(3500L, parseClock("00:00:03.500"))
        assertEquals(3500L, parseClock("00:00:03,500"))
        assertEquals(3500L, parseClock("00:00:03.50"))
        assertNull(parseClock("nope"))
        assertEquals(248, frameCountFor(8000, 31))
    }

    @Test
    fun pngIsRealRgbaAndKeepsAlpha() {
        val pixels = IntArray(32 * 24)
        pixels[0] = 0x00000000
        pixels[1] = 0x80FF8800.toInt()
        pixels[2] = 0xFF112233.toInt()
        val bytes = PngEncoder.encode(32, 24, pixels)
        assertTrue(PngEncoder.isPng(bytes))
        assertEquals(6, PngEncoder.colorType(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("<svg"))
        val image = ImageIO.read(bytes.inputStream())
        assertNotNull(image)
        assertEquals(32, image.width)
        assertEquals(24, image.height)
        assertEquals(0, image.getRGB(0, 0) ushr 24)
        val mid = image.getRGB(1, 0)
        assertEquals(0x80, (mid ushr 24) and 0xFF)
        assertEquals(0xFF, (image.getRGB(2, 0) ushr 24) and 0xFF)
    }

    @Test
    fun ffmpegCommandUsesLibx264AndAacWithoutFakeLavf() {
        val args = buildLibx264Arguments(
            pipePath = "/tmp/pipe",
            outputPath = "/tmp/out.mp4",
            width = 1920,
            height = 1080,
            fps = 31,
            videoBitrate = 11_900_000,
            durationMs = 8000,
            audioEnabled = true,
            audioBitrate = 128_000,
            sampleRate = 44_100,
            channels = 2,
            fastStart = true,
            x264Preset = "veryfast",
            threads = 2,
        )
        val joined = args.joinToString(" ")
        assertTrue(args.contains("libx264"))
        assertTrue(joined.contains("-c:a aac"))
        assertTrue(joined.contains("44100"))
        assertTrue(joined.contains("-ac 2"))
        assertTrue(joined.contains("+faststart"))
        assertTrue(joined.contains("11900000"))
        assertTrue(joined.contains("anullsrc"))
        assertFalse(joined.contains("Lavf"))
        assertFalse(joined.contains("libx264".let { "" }))
        val silent = buildRemuxArguments("/in.mp4", "/out.mp4", 8000, true, 128000, 44100, 2, true)
        assertTrue(silent.contains("aac"))
        assertTrue(silent.contains("copy"))
        assertTrue(ffmpegAbiSupported(listOf("arm64-v8a")))
        assertFalse(ffmpegAbiSupported(listOf("armeabi-v7a")))
    }

    @Test
    fun validationChecksReferenceProfile() {
        val good = ProbedMedia(
            exists = true,
            sizeBytes = 1000,
            containerParsed = true,
            streams = listOf(
                ProbedStream("video/avc", "h264", 1920, 1080, 31.0, 8_000_000, bitrate = 11_000_000),
                ProbedStream("audio/mp4a-latm", "aac", sampleRate = 44100, channelCount = 2, aacProfile = 2),
            ),
            durationUs = 8_000_000,
            validator = "test",
        )
        val expected = ExpectedMedia(1920, 1080, 31, true, 8_000_000, 11_900_000)
        assertTrue(validateMedia(good, expected).isEmpty())
        val wrongFps = good.copy(streams = good.streams.map {
            if (it.mime.startsWith("video")) it.copy(frameRate = 30.0) else it
        })
        assertTrue(validateMedia(wrongFps, expected).any { it.message.contains("Frame rate") })
        val noAudio = good.copy(streams = good.streams.filter { it.mime.startsWith("video") })
        assertTrue(validateMedia(noAudio, expected).any { it.message.contains("audio") })
        val missing = good.copy(exists = false, sizeBytes = 0)
        assertTrue(validateMedia(missing, expected).any { it.message.contains("does not exist") })
    }

    @Test
    fun memoryStorageThermalAndCancel() {
        val heap = 192L * 1024 * 1024
        assertTrue(assessBitmapMemory(heap, 1920, 1080).allowed)
        assertFalse(assessBitmapMemory(heap, 1921, 1080).allowed)
        assertFalse(assessBitmapMemory(32L * 1024 * 1024, 2560, 1440).allowed)
        val storage = planStorage(2_000_000, 1920, 1080, 5, true, 8_000_000, 60_000)
        assertFalse(storage.ok)
        assertEquals(ThermalBand.CRITICAL, thermalBand(4))
        assertEquals(ThermalBand.HOT, thermalBand(3))
        assertNull(assessLength(8_000, 31).warning)
        assertNotNull(assessLength(30 * 60 * 1000, 30).warning)
        val token = CancelToken()
        token.cancel()
        try {
            token.check()
            throw AssertionError("expected cancel")
        } catch (_: ExportCancelled) {
        }
        val dir = File("build/tmp-unit").apply { mkdirs() }
        val file = File(dir, "partial.mp4").apply { writeText("nope") }
        val tracker = TempFileTracker()
        tracker.track(file)
        assertEquals(1, tracker.deleteAll())
        assertFalse(file.exists())
    }

    @Test
    fun referencePresetPinsTheRequestedProfile() {
        val settings = applyPreset(VideoSettings(), VideoPreset.REFERENCE, ffmpegAbiAvailable = true)
        assertEquals(1920, settings.width)
        assertEquals(1080, settings.height)
        assertEquals(31, settings.fps)
        assertEquals(11_900_000, settings.videoBitrate)
        assertEquals(VideoEncoderChoice.FFMPEG_LIBX264, settings.encoder)
        assertTrue(settings.audioEnabled)
        assertEquals(44_100, settings.sampleRate)
        assertEquals(2, settings.channels)
        assertEquals(128_000, settings.audioBitrate)
        val fallback = applyPreset(VideoSettings(), VideoPreset.REFERENCE, ffmpegAbiAvailable = false)
        assertEquals(VideoEncoderChoice.DEVICE_MEDIACODEC, fallback.encoder)
        val mobile = applyPreset(VideoSettings(), VideoPreset.MOBILE_SAFE, true)
        assertEquals(24, mobile.fps)
        assertEquals(4_000_000, mobile.videoBitrate)
        assertFalse(mobile.audioEnabled)
    }

    @Test
    fun undoRestoresThePreviousProject() {
        val stack = UndoStack<String>()
        stack.snapshot("a")
        assertEquals("a", stack.undo("b"))
        assertEquals("b", stack.redo("a"))
    }
}
