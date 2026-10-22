package com.srtcolorsection.studio.domain

import java.util.UUID

object SectionEngine {
    fun newId(): String = UUID.randomUUID().toString()

    fun group(
        cues: List<SubtitleCue>,
        gapMs: Long = 1_200L,
        maxSectionMs: Long = 30_000L,
        idFactory: () -> String = { newId() },
    ): List<TimelineSection> {
        if (cues.isEmpty()) return emptyList()
        val indexed = cues.withIndex().sortedBy { it.value.startMs }
        val groups = mutableListOf<MutableList<IndexedValue<SubtitleCue>>>()
        var current = mutableListOf(indexed.first())
        groups += current
        for (item in indexed.drop(1)) {
            val cue = item.value
            val first = current.first().value
            val previous = current.last().value
            val gap = cue.startMs - previous.endMs
            val projected = cue.endMs - first.startMs
            val close = gap <= gapMs || cue.startMs < previous.endMs
            if (close && projected <= maxSectionMs) {
                current += item
            } else {
                current = mutableListOf(item)
                groups += current
            }
        }
        return groups.mapIndexed { index, group ->
            TimelineSection(
                id = idFactory(),
                name = "Section ${index + 1}",
                startMs = group.minOf { it.value.startMs },
                endMs = group.maxOf { it.value.endMs },
                colorArgb = Palette.colorFor(index),
                cueIndices = group.map { it.index }.sorted(),
                displayOrder = index,
            )
        }
    }

    fun rebind(project: Project): Project {
        val assignment = HashMap<Int, String>()
        project.cues.forEachIndexed { index, cue ->
            var bestId: String? = null
            var bestOverlap = 0L
            for (section in project.sections) {
                val overlap = minOf(cue.endMs, section.endMs) - maxOf(cue.startMs, section.startMs)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    bestId = section.id
                }
            }
            if (bestId != null && bestOverlap > 0L) assignment[index] = bestId
        }
        val sections = project.sections.map { section ->
            section.copy(
                cueIndices = assignment.filterValues { it == section.id }.keys.sorted(),
            )
        }
        return project.copy(sections = normalize(sections))
    }

    fun normalize(sections: List<TimelineSection>): List<TimelineSection> {
        val sorted = sections.sortedWith(compareBy({ it.displayOrder }, { it.startMs }, { it.id }))
        return sorted.mapIndexed { index, section -> section.copy(displayOrder = index) }
    }

    fun rename(project: Project, id: String, name: String): EditResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return EditResult.Rejected("Section name cannot be empty")
        return replace(project, id) { it.copy(name = trimmed.take(80)) }
    }

    fun recolor(project: Project, id: String, colorArgb: Int): EditResult =
        replace(project, id) { it.copy(colorArgb = colorArgb) }

    fun updateNotes(project: Project, id: String, notes: String): EditResult =
        replace(project, id) { it.copy(notes = notes.take(500)) }

    fun updateTimes(project: Project, id: String, startMs: Long, endMs: Long): EditResult {
        if (startMs < 0L) return EditResult.Rejected("Start time cannot be negative")
        if (endMs <= startMs) return EditResult.Rejected("End time must be after the start time")
        val current = project.sections.find { it.id == id }
            ?: return EditResult.Rejected("Section not found")
        val candidate = current.copy(startMs = startMs, endMs = endMs)
        val overlap = project.sections.firstOrNull {
            it.id != id && rangesOverlap(candidate.startMs, candidate.endMs, it.startMs, it.endMs)
        }
        if (overlap != null) {
            return EditResult.Rejected("That range overlaps ${overlap.name}")
        }
        val updated = project.sections.map { if (it.id == id) candidate else it }
        return EditResult.Ok(rebind(project.copy(sections = updated)))
    }

    fun split(project: Project, id: String, atMs: Long, idFactory: () -> String = { newId() }): EditResult {
        val section = project.sections.find { it.id == id }
            ?: return EditResult.Rejected("Section not found")
        if (atMs <= section.startMs || atMs >= section.endMs) {
            return EditResult.Rejected("Split time must be inside the section")
        }
        val right = TimelineSection(
            id = idFactory(),
            name = "${section.name} split".take(80),
            startMs = atMs,
            endMs = section.endMs,
            colorArgb = Palette.next(project.sections.map { it.colorArgb }),
            displayOrder = section.displayOrder + 1,
            notes = section.notes,
        )
        val left = section.copy(endMs = atMs)
        val rebuilt = project.sections.flatMap { existing ->
            when {
                existing.id == id -> listOf(left, right)
                existing.displayOrder > section.displayOrder ->
                    listOf(existing.copy(displayOrder = existing.displayOrder + 1))
                else -> listOf(existing)
            }
        }
        return EditResult.Ok(rebind(project.copy(sections = rebuilt)))
    }

    fun mergeWithNext(project: Project, id: String): EditResult {
        val ordered = project.sections.sortedBy { it.startMs }
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return EditResult.Rejected("Section not found")
        if (index == ordered.lastIndex) return EditResult.Rejected("There is no following section to merge")
        val left = ordered[index]
        val right = ordered[index + 1]
        if (right.startMs < left.startMs) return EditResult.Rejected("Sections are not in time order")
        val merged = left.copy(
            endMs = maxOf(left.endMs, right.endMs),
            notes = listOf(left.notes, right.notes).filter { it.isNotBlank() }.joinToString("\n").take(500),
        )
        val kept = project.sections.filter { it.id != left.id && it.id != right.id } + merged
        return EditResult.Ok(rebind(project.copy(sections = kept)))
    }

    fun move(project: Project, id: String, direction: Int): EditResult {
        val ordered = normalize(project.sections)
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0) return EditResult.Rejected("Section not found")
        if (target !in ordered.indices) return EditResult.Rejected("Section is already at the edge of the list")
        val swapped = ordered.toMutableList()
        val item = swapped.removeAt(index)
        swapped.add(target, item)
        val renumbered = swapped.mapIndexed { order, section -> section.copy(displayOrder = order) }
        return EditResult.Ok(project.copy(sections = renumbered))
    }

    fun delete(project: Project, id: String): EditResult {
        if (project.sections.none { it.id == id }) return EditResult.Rejected("Section not found")
        return EditResult.Ok(rebind(project.copy(sections = project.sections.filter { it.id != id })))
    }

    fun addCustom(project: Project, idFactory: () -> String = { newId() }): EditResult {
        val ordered = project.sections.sortedBy { it.startMs }
        var slotStart = 0L
        var slotEnd = 2_000L
        if (ordered.isNotEmpty()) {
            var placed = false
            if (ordered.first().startMs >= 800L) {
                slotStart = 0L
                slotEnd = minOf(2_000L, ordered.first().startMs)
                placed = slotEnd - slotStart >= 400L
            }
            if (!placed) {
                for (i in 0 until ordered.lastIndex) {
                    val gapStart = ordered[i].endMs
                    val gapEnd = ordered[i + 1].startMs
                    if (gapEnd - gapStart >= 500L) {
                        slotStart = gapStart
                        slotEnd = minOf(gapStart + 2_000L, gapEnd)
                        placed = true
                        break
                    }
                }
            }
            if (!placed) {
                slotStart = ordered.last().endMs
                slotEnd = slotStart + 2_000L
            }
        }
        if (slotEnd <= slotStart) return EditResult.Rejected("Could not find a valid time range")
        val section = TimelineSection(
            id = idFactory(),
            name = "Section ${ordered.size + 1}",
            startMs = slotStart,
            endMs = slotEnd,
            colorArgb = Palette.next(project.sections.map { it.colorArgb }),
            displayOrder = project.sections.size,
        )
        return EditResult.Ok(rebind(project.copy(sections = project.sections + section)))
    }

    fun numbered(sections: List<TimelineSection>): List<Pair<Int, TimelineSection>> =
        normalize(sections).mapIndexed { index, section -> (index + 1) to section }

    private fun replace(project: Project, id: String, transform: (TimelineSection) -> TimelineSection): EditResult {
        if (project.sections.none { it.id == id }) return EditResult.Rejected("Section not found")
        return EditResult.Ok(project.copy(sections = project.sections.map { if (it.id == id) transform(it) else it }))
    }

    private fun rangesOverlap(a0: Long, a1: Long, b0: Long, b1: Long): Boolean = a0 < b1 && b0 < a1
}
