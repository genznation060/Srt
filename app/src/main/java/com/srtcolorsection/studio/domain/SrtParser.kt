package com.srtcolorsection.studio.domain

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader

object SrtParser {
    private val TIMESTAMP = Regex(
        """(\d{1,3}):([0-5]\d):([0-5]\d)[,.](\d{1,3})\s*-->\s*(\d{1,3}):([0-5]\d):([0-5]\d)[,.](\d{1,3})""",
    )

    fun parse(text: String): SrtParseResult {
        val stripped = if (text.startsWith("\uFEFF")) text.substring(1) else text
        return parse(StringReader(stripped))
    }

    fun parse(reader: Reader): SrtParseResult {
        val buffered = if (reader is BufferedReader) reader else reader.buffered()
        val cues = mutableListOf<SubtitleCue>()
        val warnings = mutableListOf<ParseWarning>()
        var start = -1L
        var end = -1L
        val body = StringBuilder()
        var lineNo = 0
        var block = 0

        fun finish() {
            if (start < 0L) {
                body.clear()
                return
            }
            block += 1
            val text = body.toString().trim()
            if (end <= start) {
                warnings += ParseWarning(
                    "Skipped cue with invalid duration ${formatClock(start)} → ${formatClock(end)}",
                    lineNo,
                )
            } else {
                if (text.isEmpty()) {
                    warnings += ParseWarning("Cue at ${formatClock(start)} has no text", lineNo)
                }
                cues += SubtitleCue(
                    index = cues.size + 1,
                    startMs = start,
                    endMs = end,
                    text = text,
                )
            }
            start = -1L
            end = -1L
            body.clear()
        }

        buffered.useLines { lines ->
            lines.forEach { raw ->
                lineNo += 1
                val line = raw.trimEnd('\r')
                val trimmed = line.trim()
                val match = TIMESTAMP.find(trimmed)
                if (match != null && match.range.first == 0) {
                    if (start >= 0L) finish()
                    val parsedStart = partsToMs(match.groupValues, 1)
                    val parsedEnd = partsToMs(match.groupValues, 5)
                    if (parsedStart == null || parsedEnd == null) {
                        warnings += ParseWarning("Skipped malformed timestamp on line $lineNo", lineNo)
                        start = -1L
                        end = -1L
                    } else {
                        start = parsedStart
                        end = parsedEnd
                    }
                } else if (trimmed.isEmpty()) {
                    finish()
                } else if (start >= 0L) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(trimmed)
                } else if (trimmed.toIntOrNull() != null) {
                    // SubRip sequence number.
                } else if (trimmed.isNotEmpty()) {
                    warnings += ParseWarning("Skipped unrecognized line $lineNo", lineNo)
                }
            }
        }
        finish()

        val ordered = cues.sortedWith(compareBy({ it.startMs }, { it.index }))
            .mapIndexed { i, cue -> cue.copy(index = i + 1) }
        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val current = ordered[i]
            if (current.startMs < previous.endMs) {
                warnings += ParseWarning(
                    "Overlapping cues ${previous.index} and ${current.index}",
                    0,
                )
            }
        }
        return SrtParseResult(ordered, warnings)
    }

    private fun partsToMs(groups: List<String>, offset: Int): Long? {
        val hours = groups[offset].toLongOrNull() ?: return null
        val minutes = groups[offset + 1].toLongOrNull() ?: return null
        val seconds = groups[offset + 2].toLongOrNull() ?: return null
        val fraction = groups[offset + 3]
        if (minutes > 59 || seconds > 59) return null
        val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }
}
