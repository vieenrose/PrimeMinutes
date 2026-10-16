package studio.primeminutes.core.reader

import org.json.JSONObject

/**
 * Model guardrail: how much of what the reader wrote in one job was off the protocol its card
 * describes. Counted per non-blank reply line (the card's [GuardCard.ignoreActs], the end-of-turn
 * act, are not lines of content):
 *  - [nonNote]: a line that is not a NOTE (prose, another act, garbage) — never used;
 *  - [malformed]: a NOTE whose argument does not parse — dropped;
 *  - [unknownTag]: a NOTE with a tag the card does not list — kept (the minutes and the panel show
 *    unknown keys rather than lose them) but counted, it is the first sign of a drifting model.
 * A new model or a regressed export shows here first: logged per job, a warning above the card's
 * [GuardCard.maxOffProtocolRate], saved next to the meeting, shown in the AI notes panel (DEV).
 */
data class ReaderGuard(
    val lines: Int = 0,
    val notes: Int = 0,
    val nonNote: Int = 0,
    val unknownTag: Int = 0,
    val malformed: Int = 0,
) {
    val offProtocol: Int get() = nonNote + unknownTag + malformed
    val rate: Double get() = if (lines == 0) 0.0 else offProtocol.toDouble() / lines

    /** Above the card's threshold, once there are enough lines for a rate to mean something. */
    fun exceeds(card: GuardCard): Boolean = lines >= card.minLines && rate > card.maxOffProtocolRate

    fun summary(): String = "lines=$lines notes=$notes nonNote=$nonNote unknownTag=$unknownTag malformed=$malformed rate=" +
        String.format(java.util.Locale.ROOT, "%.2f", rate)

    fun toJson(): JSONObject = JSONObject().put("lines", lines).put("notes", notes).put("nonNote", nonNote)
        .put("unknownTag", unknownTag).put("malformed", malformed)

    companion object {
        fun fromJson(o: JSONObject) = ReaderGuard(
            o.optInt("lines"), o.optInt("notes"), o.optInt("nonNote"), o.optInt("unknownTag"), o.optInt("malformed"),
        )
    }
}

/** The model card's guardrail settings (reader.guard). Absent from a card: no tag check, 20 %, 10 lines. */
data class GuardCard(
    /** The tags the model was trained to write; empty = any tag is fine. */
    val knownTags: Set<String> = emptySet(),
    /** Acts that end a turn rather than say something (NEXT). */
    val ignoreActs: Set<String> = emptySet(),
    val maxOffProtocolRate: Double = 0.2,
    val minLines: Int = 10,
) {
    fun known(tag: String?): Boolean = tag == null || knownTags.isEmpty() || tag.uppercase() in knownTags
}
