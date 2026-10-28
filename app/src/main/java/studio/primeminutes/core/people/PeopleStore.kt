package studio.primeminutes.core.people

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.library.writeTextAtomic
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.data.SpeakerName
import java.io.File

/**
 * The people directory on disk (Android): `filesDir/people/people.json` ([PeopleCodec]), and per
 * meeting `library/<id>/speakers.json`, the meeting's named speakers and their person links. That
 * sidecar is the authority for a library meeting's names: the copy inside `session.m4a` is updated
 * whenever the meeting is saved again, and [overlay] lays the sidecar over it on every read, so a
 * directory edit (rename everywhere, merge, delete) never has to rebuild the audio file.
 *
 * Local only: both live in the app's files, which `backup_rules.xml` already keeps out of backups.
 */
object PeopleStore {
    private const val TAG = "primeminutes-people"
    const val LINKS = "speakers.json"

    private val lock = Any()
    @Volatile private var filesDir: File? = null
    private val _doc = MutableStateFlow(PeopleDoc())
    val doc: StateFlow<PeopleDoc> = _doc.asStateFlow()

    /** Bumped on every change of the directory or of a meeting's links (caches key on it). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Meetings whose links a directory edit rewrote: the open session re-reads its names. */
    private val _changed = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8)
    val changed: SharedFlow<Set<String>> = _changed.asSharedFlow()

    private fun file(dir: File) = File(dir, "people/people.json")

    /** Loads the directory once per process (cheap: one small JSON file). */
    fun init(context: Context) = ensure(context.applicationContext.filesDir)

    private fun ensure(dir: File) {
        if (filesDir != null) return
        synchronized(lock) {
            if (filesDir != null) return
            _doc.value = PeopleCodec.decode(runCatching { file(dir).takeIf { it.exists() }?.readText() }.getOrNull())
            filesDir = dir
        }
    }

    /** One script for Han names (繁/简) when comparing: character-level t2s, like the search's. */
    fun fold(context: Context): (String) -> String {
        val cc = runCatching { OpenCcConverter.getTranscriptSimplified(context) }.getOrNull()
        return { s -> cc?.convert(s) ?: s }
    }

    /** Applies [f] to the directory and writes it (atomic). Returns the new document. */
    fun mutate(context: Context, f: (PeopleDoc) -> PeopleDoc): PeopleDoc {
        init(context)
        val d = synchronized(lock) {
            val nd = f(_doc.value)
            if (nd != _doc.value) {
                runCatching { file(filesDir!!).apply { parentFile?.mkdirs() }.writeTextAtomic(PeopleCodec.encode(nd)) }
                    .onFailure { Log.w(TAG, "could not write the people directory", it) }
                _doc.value = nd
            }
            nd
        }
        _version.value++
        return d
    }

    fun create(context: Context, name: String, me: Boolean = false): Person {
        var made: Person? = null
        mutate(context) { d -> PeopleOps.create(d, name, now(), me = me).also { made = it.second }.first }
        return made!!
    }

    fun touch(context: Context, id: String) { mutate(context) { PeopleOps.touch(it, id, now()) } }

    /**
     * The people of one meeting: each linked speaker → its person (unnamed speakers and plain labels
     * are absent). The API later features build on (e.g. "email participants").
     */
    fun peopleOf(context: Context, entryDir: File): Map<Int, Person> {
        init(context)
        val d = doc.value
        return (readLinks(entryDir) ?: emptyMap()).mapNotNull { (sid, l) -> d.byId(l.person)?.let { sid to it } }.toMap()
    }

    private fun now() = System.currentTimeMillis() / 1000

    // --- per-meeting links ---------------------------------------------------------------------

    fun readLinks(entryDir: File): Map<Int, SpeakerLink>? =
        runCatching { File(entryDir, LINKS).takeIf { it.isFile }?.readText() }.getOrNull()?.let(PeopleCodec::decodeLinks)

    fun writeLinks(entryDir: File, links: Map<Int, SpeakerLink>) {
        runCatching { File(entryDir, LINKS).writeTextAtomic(PeopleCodec.encodeLinks(links)) }
            .onFailure { Log.w(TAG, "could not write the speakers of ${entryDir.name}", it) }
        _version.value++
    }

    fun linksOf(names: Map<Int, SpeakerName>): Map<Int, SpeakerLink> =
        names.filterValues { it.name.isNotBlank() }.mapValues { (_, n) -> SpeakerLink(n.name, n.person) }

    /** Saves a library meeting's names as its sidecar (every save path of the library goes through here). */
    fun saveNames(entryDir: File, names: Map<Int, SpeakerName>) {
        if (!entryDir.isDirectory) return
        val links = linksOf(names)
        if (links.isEmpty() && !File(entryDir, LINKS).exists()) return
        if (readLinks(entryDir) == links) return
        writeLinks(entryDir, links)
    }

    /** The names a session file shows: its sidecar (a library meeting) over its embedded names, each
     *  linked speaker named by the directory. [sessionFile] outside the library: the embedded names. */
    fun overlay(sessionFile: File, names: Map<Int, SpeakerName>): Map<Int, SpeakerName> {
        val entryDir = sessionFile.parentFile ?: return names
        val lib = entryDir.parentFile ?: return names
        if (lib.name != "library") return names
        lib.parentFile?.let(::ensure)
        val links = readLinks(entryDir) ?: linksOf(names)
        return resolve(links)
    }

    /** Links as speaker names, the directory's name for each linked speaker. */
    fun resolve(links: Map<Int, SpeakerLink>): Map<Int, SpeakerName> =
        LinkOps.resolve(links, _doc.value).mapValues { (_, l) -> SpeakerName(l.name, confidence = "user", person = l.person) }

    /** Every library meeting's links (sidecars only; meetings never named have none). */
    fun allLinks(context: Context): Map<String, Map<Int, SpeakerLink>> {
        val root = SessionLibrary.root(context)
        val out = HashMap<String, Map<Int, SpeakerLink>>()
        root.listFiles()?.forEach { d -> readLinks(d)?.takeIf { it.isNotEmpty() }?.let { out[d.name] = it } }
        return out
    }

    /** Person → meetings (entry ids) it is linked in. */
    fun meetingsByPerson(links: Map<String, Map<Int, SpeakerLink>>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for ((e, m) in links) for (l in m.values) l.person?.let { out.getOrPut(it) { HashSet() } += e }
        return out
    }

    fun usage(context: Context, links: Map<String, Map<Int, SpeakerLink>> = allLinks(context)): Map<String, PeoplePicker.Usage> {
        val entries = SessionLibrary.list(context).associateBy { it.id }
        return meetingsByPerson(links).mapValues { (_, ids) ->
            val newest = ids.mapNotNull { entries[it] }.maxByOrNull { it.createdAt }
            PeoplePicker.Usage(ids.count { it in entries }, (newest?.createdAt ?: 0L) / 1000, newest?.title)
        }
    }

    // --- directory edits that touch meetings -----------------------------------------------------

    /** Rewrites every meeting's links with [f]; the meetings that changed are re-indexed and announced. */
    private fun rewriteMeetings(context: Context, f: (Map<Int, SpeakerLink>) -> Map<Int, SpeakerLink>): Set<String> {
        val touched = HashSet<String>()
        for ((id, links) in allLinks(context)) {
            val nl = f(links)
            if (nl != links) {
                writeLinks(File(SessionLibrary.root(context), id), nl)
                touched += id
            }
        }
        for (id in touched) studio.primeminutes.core.embed.LibraryIndexer.request(context, id)
        if (touched.isNotEmpty()) _changed.tryEmit(touched)
        return touched
    }

    fun renameEverywhere(context: Context, id: String, name: String) {
        val n = name.trim().ifBlank { return }
        mutate(context) { PeopleOps.rename(it, id, n) }
        rewriteMeetings(context) { LinkOps.rename(it, id, n) }
    }

    fun merge(context: Context, from: String, into: String) {
        val target = doc.value.byId(into) ?: return
        mutate(context) { PeopleOps.merge(it, from, into) }
        rewriteMeetings(context) { LinkOps.merge(it, from, target) }
        VoiceHooks.onMerge(context, from, into)
    }

    fun delete(context: Context, id: String, clearNames: Boolean) {
        val gone = doc.value.byId(id)
        mutate(context) { PeopleOps.delete(it, id) }
        rewriteMeetings(context) { LinkOps.delete(it, id, clearNames) }
        VoiceHooks.onDeletePerson(context, id)
        // "Delete Mark" removes Mark's address everywhere in the app: the share history too.
        val keys = gone?.emails?.map { EmailRules.key(it.address) }?.toSet().orEmpty()
        SessionLibrary.root(context).listFiles()?.forEach { d ->
            val f = File(d, studio.primeminutes.core.share.ShareHistory.FILE)
            if (!f.isFile) return@forEach
            val h = studio.primeminutes.core.share.ShareHistory.decode(runCatching { f.readText() }.getOrNull())
            val nh = studio.primeminutes.core.share.ShareHistory.scrub(h, id, keys)
            if (nh != h) runCatching { f.writeTextAtomic(studio.primeminutes.core.share.ShareHistory.encode(nh)) }
        }
    }

    // --- contact side (docs/design/speaker-contacts.md) -------------------------------------------

    fun addEmail(context: Context, id: String, address: String, default: Boolean = false) {
        mutate(context) { PeopleOps.addEmail(it, id, address, default = default) }
    }

    fun removeEmail(context: Context, id: String, address: String) { mutate(context) { PeopleOps.removeEmail(it, id, address) } }

    fun makeDefaultEmail(context: Context, id: String, address: String) { mutate(context) { PeopleOps.makeDefault(it, id, address) } }

    fun linkContact(context: Context, id: String, c: PickedContact, chosen: String?) {
        mutate(context) { PeopleOps.linkContact(it, id, c, now(), chosen) }
    }

    fun unlinkContact(context: Context, id: String, removeEmails: Boolean) { mutate(context) { PeopleOps.unlinkContact(it, id, removeEmails) } }

    /** A new person from a picked contact: [name] (the contact's, possibly edited), its addresses and link. */
    fun createFromContact(context: Context, name: String, c: PickedContact, chosen: String?): Person {
        val p = create(context, name)
        linkContact(context, p.id, c, chosen)
        return doc.value.byId(p.id) ?: p
    }

    // --- OS contacts (people == contacts) -----------------------------------------------------

    /** A new person backed by a new OS contact named [name]; null when Contacts refused (no permission, provider error). */
    fun createWithContact(context: Context, name: String): Person? {
        val c = ContactsProvider.insert(context, name.trim()) ?: return null
        return createForContact(context, c)
    }

    /** The person linked to contact [c] (an existing one when already linked, else a new one). */
    fun createForContact(context: Context, c: OsContact): Person {
        init(context)
        doc.value.people.firstOrNull { ContactLinks.isLinked(it) && (it.contact!!.key == c.key || it.contact.id == c.id) }?.let { return it }
        val p = create(context, c.name)
        mutate(context) { ContactLinks.apply(it, p.id, LinkState.Linked(c), now()) }
        return doc.value.byId(p.id) ?: p
    }

    /**
     * Re-reads every linked person from the address book: names and addresses follow the contact,
     * a deleted contact leaves its person unlinked (name kept). Renames reach the meetings. No-op
     * without the permission. Run at launch/resume and when a screen that shows addresses opens.
     */
    fun syncContacts(context: Context) {
        init(context)
        if (!ContactsProvider.granted(context)) return
        val before = doc.value
        val states = before.people.filter { it.contact?.platform == "android" }.associate { it.id to ContactsProvider.resolve(context, it.contact) }
        if (states.isEmpty()) return
        val after = mutate(context) { d -> states.entries.fold(d) { acc, (id, st) -> ContactLinks.apply(acc, id, st, now()) } }
        for (p in after.people) {
            val old = before.byId(p.id) ?: continue
            if (old.name != p.name) rewriteMeetings(context) { LinkOps.rename(it, p.id, p.name) }
        }
    }

    /** Adds [address] to [id]'s contact (the mail sheet's "add an address"), then reads it back. */
    fun addEmailToContact(context: Context, id: String, address: String): Boolean {
        val p = doc.value.byId(id) ?: return false
        val st = ContactsProvider.resolve(context, p.contact)
        if (st !is LinkState.Linked) return false
        val ok = ContactsProvider.addEmails(context, st.contact.id, listOf(EmailRules.normalize(address)))
        syncContacts(context)
        return ok
    }

    private fun prefs(context: Context) = context.getSharedPreferences("people_contacts", Context.MODE_PRIVATE)

    /** The "Add N people to Contacts?" card: how many app-only people there are (0 once dismissed or none). */
    fun contactsCardCount(context: Context): Int {
        init(context)
        if (prefs(context).getBoolean("card_dismissed", false)) return 0
        return ContactMigration.candidates(doc.value).size
    }

    fun dismissContactsCard(context: Context) { prefs(context).edit().putBoolean("card_dismissed", true).apply(); _version.value++ }

    /**
     * Moves the app-only people to Contacts ([ContactMigration]): matched ones are linked to the
     * existing contact (their addresses added to it), the others are created. The person ids stay, so
     * voiceprints and meetings stay attached. Returns how many were linked.
     */
    fun migrateToContacts(context: Context): Int {
        init(context)
        if (!ContactsProvider.granted(context)) return 0
        val plan = ContactMigration.plan(ContactMigration.candidates(doc.value), ContactsProvider.all(context), fold(context))
        var n = 0
        for (step in plan) {
            val c = when (step) {
                is ContactMigration.Step.Match -> step.contact.also { ContactsProvider.addEmails(context, it.id, step.missingEmails) }
                is ContactMigration.Step.Create -> ContactsProvider.insert(context, step.person.name, step.person.emails.map { it.address })
            } ?: continue
            val fresh = ContactsProvider.byId(context, c.id) ?: c
            mutate(context) { ContactLinks.apply(it, step.person.id, LinkState.Linked(fresh), now()) }
            n++
        }
        Log.i(TAG, "contacts migration: $n of ${plan.size} linked (${plan.count { it is ContactMigration.Step.Match }} matched)")
        syncContacts(context)
        dismissContactsCard(context)
        return n
    }

    /** Marks [id] as the phone's owner (one Me at most). */
    fun setMe(context: Context, id: String) {
        mutate(context) { d -> d.copy(people = d.people.map { it.copy(me = it.id == id) }) }
    }

    /** "This meeting's [personId] is someone else": its speakers there go to a new person [newName]. */
    fun split(context: Context, entryId: String, personId: String, newName: String, withContact: Boolean = false): Person? {
        val dir = File(SessionLibrary.root(context), entryId)
        val links = readLinks(dir) ?: return null
        val p = if (withContact) createWithContact(context, newName) ?: return null else create(context, newName)
        val nl = LinkOps.split(links, personId, p)
        writeLinks(dir, nl)
        studio.primeminutes.core.embed.LibraryIndexer.request(context, entryId)
        _changed.tryEmit(setOf(entryId))
        VoiceHooks.onSplit(context, entryId, personId, p.id)
        return p
    }

    fun setNote(context: Context, id: String, note: String?) { mutate(context) { PeopleOps.setNote(it, id, note) } }

    fun dismissMigrationCard(context: Context) { mutate(context) { it.copy(migrationFound = 0) } }

    /** A meeting was deleted: its links go with its directory; announce it (counts, voice samples). */
    fun onMeetingDeleted(context: Context, entryId: String) {
        _version.value++
        VoiceHooks.onMeetingDeleted(context, entryId)
    }

    // --- migration -------------------------------------------------------------------------------

    /**
     * Gathers the speaker names saved before the directory existed (once): identical names become one
     * person ([PeopleMigration]). Reads every finished meeting's embedded names, so it runs in the
     * background at launch. Meetings that already have a sidecar keep it.
     */
    suspend fun migrateIfNeeded(context: Context) {
        init(context)
        if (doc.value.migrated) return
        val meetings = ArrayList<PeopleMigration.Meeting>()
        for (e in SessionLibrary.list(context)) {
            if (e.status != SessionLibrary.Status.DONE || !e.sessionFile.exists()) continue
            val existing = readLinks(e.dir)
            val names = existing ?: studio.primeminutes.core.session.PrimeMinutesSession.peek(e.sessionFile)?.speakerNames?.let(::linksOf) ?: continue
            if (names.isNotEmpty()) meetings += PeopleMigration.Meeting(e.id, e.createdAt / 1000, names)
        }
        val fold = fold(context)
        var result: PeopleMigration.Result? = null
        mutate(context) { d -> PeopleMigration.run(d, meetings, fold, now()).also { result = it }.doc }
        val r = result ?: return
        for ((id, links) in r.links) {
            val dir = File(SessionLibrary.root(context), id)
            val cur = readLinks(dir) ?: emptyMap()
            // Only links are added: a name the meeting had stays, now with its person.
            val merged = cur + links.filterKeys { k -> cur[k]?.person == null }
            if (merged != cur) writeLinks(dir, merged)
        }
        if (r.links.isNotEmpty()) _changed.tryEmit(r.links.keys)
        Log.i(TAG, "migration: ${r.created} people from ${meetings.size} meetings")
    }
}

/** Phase 2 voice data hooks (no-ops until a voice store is attached). */
object VoiceHooks {
    @Volatile var impl: Impl? = null

    interface Impl {
        fun onMerge(context: Context, from: String, into: String)
        fun onDeletePerson(context: Context, id: String)
        fun onSplit(context: Context, entryId: String, from: String, to: String)
        fun onMeetingDeleted(context: Context, entryId: String)
    }

    fun onMerge(context: Context, from: String, into: String) { impl?.onMerge(context, from, into) }
    fun onDeletePerson(context: Context, id: String) { impl?.onDeletePerson(context, id) }
    fun onSplit(context: Context, entryId: String, from: String, to: String) { impl?.onSplit(context, entryId, from, to) }
    fun onMeetingDeleted(context: Context, entryId: String) { impl?.onMeetingDeleted(context, entryId) }
}

/** Phase 2: a name the user confirmed (or removed) teaches (or unteaches) that person's voice. No-op without voice. */
object VoiceLinks {
    @Volatile var impl: Impl? = null

    interface Impl {
        fun confirmed(context: Context, entryId: String, speaker: Int, person: String)
        fun unlinked(context: Context, entryId: String, speaker: Int, person: String)
    }

    fun confirmed(context: Context, entryId: String?, speaker: Int, person: String) { if (entryId != null) impl?.confirmed(context, entryId, speaker, person) }
    fun unlinked(context: Context, entryId: String?, speaker: Int, person: String) { if (entryId != null) impl?.unlinked(context, entryId, speaker, person) }
}
