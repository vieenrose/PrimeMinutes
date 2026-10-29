package studio.primeminutes.core.share

/**
 * The Share sheet's choices (docs: share redesign spec v2): WHAT leaves the app, then WHERE it goes.
 * Pure (no Android) so the defaults and the file list are unit-tested.
 */
object ShareKit {

    /** The three data modalities. */
    enum class What { SESSION, PDF, AUDIO }

    /** The document format of the summary + transcript ("PDF | Word"; the last choice is remembered). */
    enum class DocFormat(val ext: String, val mime: String) {
        PDF("pdf", "application/pdf"),
        WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    }

    /** The three channels besides "other apps". */
    enum class Where { NEARBY, MAIL, OTHER }

    /** Above this, many mail servers refuse the message. */
    const val MAIL_LIMIT = 25L * 1000 * 1000

    /** A recording → the whole session; a meeting from an online source (podcast) → the PDF, no audio. */
    fun defaultWhat(online: Boolean, hasSession: Boolean): What = when {
        !hasSession -> What.AUDIO
        online -> What.PDF
        else -> What.SESSION
    }

    /** "Attach the recording" next to the PDF: off for an online source or a recording too big to mail. */
    fun attachAudioByDefault(online: Boolean, audioBytes: Long): Boolean = !online && audioBytes in 1..MAIL_LIMIT

    /** One file to send: its name and MIME type (the bytes are written by the app). */
    data class Item(val ext: String, val mime: String)

    /** The files a choice produces, in order. */
    fun items(what: What, attachAudio: Boolean, audioExt: String, format: DocFormat = DocFormat.PDF): List<Item> {
        val audio = Item(audioExt, if (audioExt == "wav") "audio/wav" else "audio/mp4")
        return when (what) {
            What.SESSION -> listOf(Item("pmin", "application/vnd.primeminutes.session"))
            What.PDF -> listOf(Item(format.ext, format.mime)) + if (attachAudio) listOf(audio) else emptyList()
            What.AUDIO -> listOf(audio)
        }
    }

    /** The bytes a choice sends ([pdfBytes] null: not built yet → unknown). */
    fun size(what: What, attachAudio: Boolean, sessionBytes: Long, audioBytes: Long, pdfBytes: Long?): Long? = when (what) {
        What.SESSION -> sessionBytes
        What.AUDIO -> audioBytes
        What.PDF -> pdfBytes?.let { it + if (attachAudio) audioBytes else 0L }
    }

    /** The MIME type of a send of [items] (one common type, else the family, else any). */
    fun mimeOf(items: List<Item>): String {
        val types = items.map { it.mime }.distinct()
        if (types.size == 1) return types[0]
        val fam = types.map { it.substringBefore('/') }.distinct()
        return if (fam.size == 1) fam[0] + "/*" else "*/*"
    }
}
