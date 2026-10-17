package studio.primeminutes.core.library

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The edits of a library meeting not yet folded into its `session.m4a`.
 *
 * Rebuilding the m4a takes 15–25 s on a Note10+ (the whole container is rewritten around the new
 * tags), so an edit that only lived there was lost to a kill in that window. Every edit is now written
 * first to `session_edits.json` in the entry directory: the session manifest itself (the JSON the
 * m4a embeds, gunzipped), in a few ms, atomically (temp file, fsync, rename). Readers
 * ([studio.primeminutes.core.session.PrimeMinutesSession.open] / `peek`) take it over the embedded copy
 * when its `edit_rev` is higher; [SessionLibrary.foldEdits] writes it into the m4a later and deletes it.
 *
 * Revisions: every write of an entry's session (an edit here, a pipeline result into the m4a) takes a
 * new `edit_rev` from [nextRev] — wall-clock ms, strictly increasing per entry — so whichever was
 * written last wins, whatever finished first. The m4a embeds the revision it was built from.
 *
 * An app version without this file reads the m4a only: the meeting as it was at the last fold, a valid
 * session. A kill during a fold leaves the previous m4a (the build renames into place) and this file.
 */
object EditJournal {
    const val FILE = "session_edits.json"
    const val REV = "edit_rev"
    private const val TAG = "EditJournal"

    /** Writes, deletes and revision numbers of every entry go through this lock (they are short). */
    private val lock = Any()
    private val lastRev = HashMap<String, Long>()

    fun file(dir: File) = File(dir, FILE)
    fun exists(dir: File) = file(dir).isFile

    /** The pending manifest and its revision, or null when none (or unreadable: ignored, kept). */
    fun read(dir: File): JSONObject? {
        val f = file(dir)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }
            .onFailure { Log.w(TAG, "unreadable edits of ${dir.name}, ignored", it) }
            .getOrNull()?.takeIf { it.optLong(REV, 0L) > 0L }
    }

    /** [journal] when it is newer than the [embedded] manifest of the same meeting, else null. */
    fun newer(journal: JSONObject?, embedded: JSONObject?): JSONObject? {
        val j = journal ?: return null
        return j.takeIf { it.optLong(REV, 0L) > (embedded?.optLong(REV, 0L) ?: 0L) }
    }

    /** A new revision for [dir]: wall-clock ms, above any revision issued or written for it before. */
    fun nextRev(dir: File, atLeast: Long = 0L): Long = synchronized(lock) {
        val onDisk = read(dir)?.optLong(REV, 0L) ?: 0L
        val rev = maxOf(System.currentTimeMillis(), (lastRev[dir.path] ?: 0L) + 1, onDisk + 1, atLeast)
        lastRev[dir.path] = rev
        rev
    }

    /** Durably writes [manifest] (which carries its [REV]) as the entry's pending edits. */
    fun write(dir: File, manifest: JSONObject) = synchronized(lock) {
        val rev = manifest.optLong(REV, 0L)
        require(rev > 0L) { "edits without a revision" }
        // An older write (a slower caller with an earlier revision) never replaces a newer one.
        val cur = read(dir)?.optLong(REV, 0L) ?: 0L
        if (cur > rev) return@synchronized
        val f = file(dir)
        val tmp = File(dir, "$FILE.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(manifest.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) { tmp.delete(); error("could not write ${f.path}") }
        syncDir(dir)
        if (rev > (lastRev[dir.path] ?: 0L)) lastRev[dir.path] = rev
    }

    /** Removes the pending edits once a session of revision [rev] or later is in the m4a. */
    fun deleteUpTo(dir: File, rev: Long): Boolean = synchronized(lock) {
        val f = file(dir)
        if (!f.exists()) return@synchronized true
        val cur = read(dir)   // null: unreadable, of no use to anyone
        if (cur != null && cur.optLong(REV, 0L) > rev) return@synchronized false   // edited again meanwhile: kept
        f.delete()
    }

    /** fsync the directory so the rename itself survives a power cut (a process kill never needed it). */
    private fun syncDir(dir: File) {
        runCatching {
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }
    }
}
