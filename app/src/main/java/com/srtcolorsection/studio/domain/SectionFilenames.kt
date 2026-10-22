package com.srtcolorsection.studio.domain

private val UNSAFE = Regex("""[^\p{L}\p{N}._-]+""")

fun sanitizeFileToken(name: String, maxLength: Int = 40): String {
    val cleaned = UNSAFE.replace(name.trim(), "_")
        .trim('_', '.', '-', ' ')
        .replace(Regex("_+"), "_")
    val cut = cleaned.take(maxLength).trim('_')
    return cut.ifBlank { "section" }
}

/**
 * Every generated name contains the section start and end timestamps.
 * Pattern only changes whether the section name is included.
 */
fun sectionPngFileName(
    section: TimelineSection,
    number: Int,
    pattern: FilenamePattern,
    taken: Set<String>,
): String {
    val num = number.coerceAtLeast(1).toString().padStart(3, '0')
    val range = "${formatFileStamp(section.startMs)}_to_${formatFileStamp(section.endMs)}"
    val stem = when (pattern) {
        FilenamePattern.NUMBER_TIMESTAMP -> "section_${num}_$range"
        FilenamePattern.NUMBER_NAME,
        FilenamePattern.NUMBER_NAME_TIMESTAMP,
        -> "section_${num}_${sanitizeFileToken(section.name)}_$range"
    }
    var candidate = "$stem.png"
    var suffix = 2
    while (candidate.lowercase() in taken.map { it.lowercase() }.toSet() || candidate in taken) {
        candidate = "${stem}_$suffix.png"
        suffix += 1
    }
    return candidate
}

fun zipBundleName(projectName: String, taken: Set<String>): String {
    val token = sanitizeFileToken(projectName, 32)
    val stem = "sections_${token}"
    var candidate = "$stem.zip"
    var suffix = 2
    while (candidate in taken) {
        candidate = "${stem}_$suffix.zip"
        suffix += 1
    }
    return candidate
}
