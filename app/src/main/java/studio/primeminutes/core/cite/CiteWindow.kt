package studio.primeminutes.core.cite

import studio.primeminutes.core.export.ExportNotes

/**
 * The few transcript turns a citation preview shows: the cited turn (same rule as the exported
 * documents, [ExportNotes.targetFor]: the last turn starting at or before the cite) with its
 * neighbours, and the window kept in place while the playing turn stays inside it.
 */
object CiteWindow {
    /** Turns shown by the preview: the cited one, one before, up to three after. */
    const val SIZE = 5
    private const val BEFORE = 1

    /** The cited turn for a cite at [sec], or null with no turns. */
    fun target(sec: Int, turnStarts: List<Double>): Int? = ExportNotes.targetFor(sec, turnStarts)

    /** Up to [size] turn indices around [index] ([before] of them ahead of it), clamped to [0, count). */
    fun around(index: Int, count: Int, size: Int = SIZE, before: Int = BEFORE): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val n = size.coerceIn(1, count)
        val start = (index.coerceIn(0, count - 1) - before).coerceIn(0, count - n)
        return start until start + n
    }

    /**
     * The window to show once playback is at [playing]: [current] while it still holds it (the panel
     * does not jump line by line), else a new one opening on the playing turn (it then reads downward).
     */
    fun follow(current: IntRange, playing: Int, count: Int, size: Int = SIZE): IntRange =
        if (playing < 0 || playing >= count || playing in current) current else around(playing, count, size, before = 0)
}
