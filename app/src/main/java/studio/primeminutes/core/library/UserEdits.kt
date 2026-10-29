package studio.primeminutes.core.library

import org.json.JSONObject
import java.io.File

/**
 * What the user hand-edited in a library meeting, kept beside it (`user_edits.json`): a re-summarize
 * asks before replacing an edited summary or action items, a re-transcribe before dropping edited
 * lines. Local only for now; the shared session manifest is expected to carry the same two booleans
 * (`userEdited: {summary, lines}`) once its v2 adds them.
 */
data class UserEdits(val summary: Boolean = false, val lines: Boolean = false) {
    companion object {
        const val FILE = "user_edits.json"

        fun read(dir: File?): UserEdits = dir?.let { File(it, FILE) }?.takeIf { it.isFile }?.let { f ->
            runCatching { JSONObject(f.readText()).let { UserEdits(it.optBoolean("summary"), it.optBoolean("lines")) } }.getOrNull()
        } ?: UserEdits()

        fun write(dir: File, e: UserEdits) {
            val f = File(dir, FILE)
            if (e == UserEdits()) { f.delete(); return }
            runCatching {
                val tmp = File(dir, "$FILE.tmp")
                tmp.writeText(JSONObject().put("summary", e.summary).put("lines", e.lines).toString())
                tmp.renameTo(f)
            }
        }
    }
}
