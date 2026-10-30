import SwiftUI
import Combine

/// Ask (RAG, experimental — Android AskEngine): a question over the library, answered by the reader model
/// from the passages ranked highest by meaning (the semantic index) and by keywords (BM25), fused by
/// reciprocal rank. Each passage carries a short tag `[[k]]`, numbered across the conversation, that the
/// model cites; the answer is published with the tags expanded to `[[id@sec]]`, which the sheet turns
/// into "title · m:ss" links.
@MainActor final class AskEngine: ObservableObject {
    static let shared = AskEngine()
    nonisolated static let noteBonus: Float = 0.05, rrfK: Float = 60

    /// `reading(n)`: the model loads (when not resident) and reads the n excerpts found — the long wait;
    /// `answering` from the first token of the answer.
    enum Phase: Equatable {
        case idle, busy, retrieving, reading(Int), answering, failed(String)
        /// A question is being worked on (the sheet shows the stage and the seconds since `startedAt`).
        var working: Bool { switch self { case .retrieving, .reading, .answering: return true; default: return false } }
    }
    /// Whole seconds since `start`, never negative (Android AskEngine.elapsedSec).
    nonisolated static func elapsedSec(_ start: Date?, _ now: Date) -> Int { start.map { max(0, Int(now.timeIntervalSince($0))) } ?? 0 }
    /// `sec` is the citation tag's whole second; `at` the passage's exact start, where a tap opens it
    /// (115 for a line starting at 115.4 s opened the line before).
    /// `k` is the short tag the model sees (`[[k]]`), unique across the conversation.
    /// `a`...`b`: the transcript utterances a passage covers (-1 for a note), so neighbours can widen it.
    /// `prompt`: the text as the model reads it (each utterance with its speaker, AskHolder.speakerText), nil: `text`.
    struct Excerpt: Identifiable { let session: Session, sec: Int, text: String, score: Float, at: Double; var k = 0; var a = -1, b = -1; var prompt: String? = nil
        var id: String { session.id.uuidString + "@\(sec)" } }
    /// Retrieval knobs (AskSelect.Retrieval; Android AskEngine.retrieval).
    nonisolated static let retrieval = AskSelect.Retrieval()
    /// `pool`: every excerpt of the conversation so far, this turn's included — what a citation may point to.
    struct Turn: Identifiable { let id = UUID(); let question: String; var answer: String; let excerpts: [Excerpt]; var done: Bool; var pool: [Excerpt] = [] }

    /// The model's `[[k]]` tags rewritten `[[id@sec]]`; a number no excerpt carries is left for the sheet to drop.
    nonisolated static func expandTags(_ answer: String, _ pool: [Excerpt]) -> String {
        let rx = try! NSRegularExpression(pattern: #"\[{1,2}(\d{1,3})\]{1,2}"#)
        let ns = answer as NSString
        var out = "", last = 0
        for m in rx.matches(in: answer, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let k = Int(ns.substring(with: m.range(at: 1))) ?? -1
            if let x = pool.first(where: { $0.k == k }) { out += "[[\(x.session.id.uuidString)@\(x.sec)]]" } else { out += ns.substring(with: m.range) }
            last = m.range.location + m.range.length
        }
        return AskSelect.dedupeTags(out + ns.substring(from: last))
    }

    /// A follow-up continues the conversation only if its prompt and a whole answer still fit the cache.
    nonisolated static func followUpFits(seqLength: Int, promptTokens: Int, answerTokens: Int, context: Int) -> Bool {
        seqLength + promptTokens + answerTokens < context
    }

    /// Reciprocal-rank fusion of the dense and keyword passages (meeting summaries left out), one score per
    /// passage: 1/(k+rank) in each list it appears in. A note outranks a chunk at equal cosine in the dense
    /// list (noteBonus); the keyword list keeps the BM25 order.
    /// `speaks`: a transcript passage (meeting, speaker id) of a person the question names gets the note bonus too.
    nonisolated static func fuse(_ dense: [LibrarySearch.Hit], _ keyword: [LibrarySearch.Hit], k: Float = rrfK,
                                 speaks: (UUID, Int?) -> Bool = { _, _ in false }) -> [Excerpt] {
        struct Key: Hashable { let id: UUID, kind: UInt8, a: Int }
        var score: [Key: Float] = [:], first: [Key: Excerpt] = [:], order: [Key] = []
        func add(_ hits: [LibrarySearch.Hit], bonus: Bool) {
            let ps = hits.flatMap { h in h.passages.filter { $0.kind != .meeting }.map { (h.session, $0) } }
            let ranked = ps.enumerated().sorted { a, b in
                func sc(_ x: (Session, LibrarySearch.Passage)) -> Float {
                    bonus ? AskSelect.denseScore(x.1.score, note: x.1.kind == .note, namedSpeaker: x.1.kind == .chunk && speaks(x.0.id, x.1.speakerId), bonus: noteBonus) : x.1.score
                }
                let sa = sc(a.element), sb = sc(b.element)
                return sa != sb ? sa > sb : a.offset < b.offset
            }.map(\.element)
            for (rank, (s, p)) in ranked.enumerated() {
                let key = Key(id: s.id, kind: p.kind.rawValue, a: p.uttIndex)
                score[key, default: 0] += 1 / (k + Float(rank + 1))
                if first[key] == nil {
                    let chunk = p.kind == .chunk
                    first[key] = Excerpt(session: s, sec: Int(p.timeSec), text: p.snippet, score: 0, at: p.timeSec, a: chunk ? p.uttIndex : -1, b: chunk ? p.uttEnd : -1)
                    order.append(key)
                }
            }
        }
        add(dense, bonus: true); add(keyword, bonus: false)
        return order.enumerated().sorted { a, b in
            let sa = score[a.element]!, sb = score[b.element]!
            return sa != sb ? sa > sb : a.offset < b.offset
        }.map { e in
            let x = first[e.element]!
            return Excerpt(session: x.session, sec: x.sec, text: x.text, score: score[e.element]!, at: x.at, a: x.a, b: x.b)
        }
    }

    @Published private(set) var phase = Phase.idle
    @Published private(set) var turns: [Turn] = []
    /// When the question in progress was asked.
    @Published private(set) var startedAt: Date?
    var busy: () -> Bool = { false }
    var recording: () -> Bool = { false }
    static var enabled: Bool { Prefs.askEnabled }
    /// Both models are on disk: the AI notes model, and the search model whose index the excerpts come from
    /// (its keyword pass too).
    static var available: Bool { EmbedHost.ready && Storage.ready(Prefs.reader) }

    private let holder = AskHolder()
    private var task: Task<Void, Never>?
    private var nextK = 1
    /// The card key of the conversation's system prompt: the first question's language (`AskLanguage`).
    var convKey: String?

    /// Milliseconds the last question's retrieval took (status.log, the latency report).
    private(set) var lastRetrievalMs = 0
    /// `meeting`: the in-meeting Ask (docs/design/in-session-search-ask.md) — retrieval over that one meeting,
    /// no meeting routing, and the answered question kept in its AskHistory.
    func ask(_ sessions: [Session], question: String, meeting: Session? = nil) {
        task?.cancel()
        if busy() { phase = .busy; return }
        let lang = AppLanguage.current.resolved
        let card = Prefs.reader.card
        let first = turns.isEmpty
        // The answer follows the question; the system prompt is the first question's language, and a follow-up
        // in another language gets the card's word for its own (the conversation's prefix stays as it is).
        let ui = Self.key(lang), want = AskLanguage.answerKey(question, ui: ui)
        if first || convKey == nil { convKey = want }
        let sys = convKey ?? want
        task = Task { [holder] in
            startedAt = Date(); phase = .retrieving
            let t0 = Date()
            var excerpts = await Task.detached(priority: .userInitiated) { meeting.map { holder.retrieveIn($0, question) } ?? holder.retrieve(sessions, question) }.value
            if Task.isCancelled { return }
            lastRetrievalMs = Int(Date().timeIntervalSince(t0) * 1000)
            StatusLog.add("ask retrieved \(excerpts.count) excerpts in \(lastRetrievalMs) ms (\(meeting != nil ? "this meeting" : "\(sessions.count) meetings"))")
            for i in excerpts.indices { excerpts[i].k = nextK; nextK += 1 }
            let pool = turns.flatMap(\.excerpts) + excerpts
            turns.append(Turn(question: question, answer: "", excerpts: excerpts, done: false, pool: pool))
            phase = .reading(excerpts.count)
            let isBusy = busy
            let result: Result<String, Error> = await Task.detached(priority: .userInitiated) {
                do {
                    let s = try holder.open()
                    s.resume()
                    let t = s.template
                    let asked = AskLanguage.asked(question, ui: ui, system: sys, answerIn: card.ask.answerIn)
                    let zh = want.hasPrefix("zh")
                    var prompt = first ? t.p0 + (card.ask.system[sys] ?? Self.system(lang, card)) + t.p1 : t.p3
                    prompt += Self.render(excerpts, zh, card) + "\n\n" + asked + t.p2
                    let tPrompt = Date()
                    let toks = s.tokenize(prompt, special: true)
                    // A follow-up that would leave no room for a whole answer, or that the cache refuses, starts a
                    // fresh conversation with this question only (the answer was cut short before).
                    if !first && !Self.followUpFits(seqLength: s.seqLength, promptTokens: toks.count, answerTokens: card.ask.answerTokens, context: s.context)
                        || s.append(toks) < 0 {
                        StatusLog.add("ask: prompt does not fit the conversation (\(s.seqLength) + \(toks.count) + \(card.ask.answerTokens)): fresh conversation")
                        s.reset()
                        let fresh = t.p0 + (card.ask.system[want] ?? Self.system(lang, card)) + t.p1 + Self.render(excerpts, zh, card) + "\n\n" +
                            AskLanguage.asked(question, ui: ui, system: want, answerIn: card.ask.answerIn) + t.p2
                        DispatchQueue.main.async { AskEngine.shared.convKey = want }
                        if s.append(s.tokenize(fresh, special: true)) < 0 { throw ReaderError(description: "prompt does not fit") }
                    }
                    let pre = s.lastStats   // the prompt's prefill (append runs its own generate call)
                    var sb = ""
                    _ = s.generateContinue(maxTokens: card.ask.answerTokens, stop: "", temp: card.sampler.temp) { piece in
                        if Task.isCancelled { s.cancel() }
                        if sb.isEmpty { DispatchQueue.main.async { if AskEngine.shared.phase.working { AskEngine.shared.phase = .answering } } }
                        sb += piece
                        let now = Self.expandTags(sb, pool)
                        DispatchQueue.main.async { AskEngine.shared.publish(now, done: false) }
                    }
                    let st = s.lastStats; StatusLog.add(String(format: "ask answered in %.1f s: prefilled %d (reused %d) in %.1f s, generated %d in %.1f s; seq %d / ctx %d", Date().timeIntervalSince(tPrompt), Int(pre[0]), Int(pre[1]), pre[3], Int(st[2]), st[4], s.seqLength, s.context))
                    #if VOX_DEV
                    // DEV: the excerpt tags and the answer, so a device run can be checked from status.log (Android logcat).
                    StatusLog.add("ask excerpts: " + excerpts.map { "[[\($0.k)]]=\($0.session.title.prefix(16))@\($0.sec)" }.joined(separator: " "))
                    StatusLog.add("ask answer: " + Self.expandTags(sb, pool).replacingOccurrences(of: "\n", with: " ⏎ "))
                    #endif
                    return .success(Self.expandTags(sb.trimmingCharacters(in: .whitespacesAndNewlines), pool))
                } catch { return .failure(error) }
            }.value
            switch result {
            case .success(let a):
                publish(a, done: true); phase = isBusy() && a.isEmpty ? .busy : .idle
                if let m = meeting, !a.isEmpty, !Task.isCancelled, Dev.env["VOX_ASK_NOHISTORY"] == nil {   // DEV: device checks on a real library leave it untouched
                    let item = AskHistory.Item(q: question.trimmingCharacters(in: .whitespacesAndNewlines), a: a, t: Date().timeIntervalSince1970, x: excerpts.map { [Double($0.sec), $0.at] })
                    Task.detached { AskHistory.append(m.id, item) }
                }
            case .failure(let e): publish(turns.last?.answer ?? "", done: true); phase = .failed("\(e)")
            }
        }
    }

    private func publish(_ answer: String, done: Bool) {
        guard !turns.isEmpty else { return }
        turns[turns.count - 1].answer = answer; turns[turns.count - 1].done = done
    }
    /// Stops the answer in progress (the model stays loaded for a follow-up).
    func cancel() { holder.cancel(); task?.cancel(); task = nil; if !turns.isEmpty { turns[turns.count - 1].done = true }; if phase != .busy { phase = .idle } }
    /// Forgets the conversation and frees the reader model (the sheet closed).
    func release() { cancel(); turns = []; nextK = 1; convKey = nil; phase = .idle; let h = holder; Task.detached { h.close() } }
    func reset() { cancel(); turns = []; nextK = 1; convKey = nil; phase = .idle; holder.reset() }

    /// The excerpts as the reader sees them. For an answer outside Chinese, amounts are written in digits (ChineseAmounts).
    nonisolated static func render(_ xs: [Excerpt], _ zh: Bool, _ card: ModelCard) -> String {
        if xs.isEmpty { return card.ask.noExcerpts + "\n" }
        return xs.map { x in
            let t = (x.prompt ?? x.text).trimmingCharacters(in: .whitespacesAndNewlines)
            return "[[\(x.k)]] \(x.session.title) · \(ReaderProtocol.formatTs(x.sec))\n\(zh ? t : ChineseAmounts.toDigits(t))\n\n"
        }.joined()
    }
    /// The card's system prompt for an interface language (the fallback when the card lacks a key).
    nonisolated static func system(_ lang: AppLanguage, _ card: ModelCard) -> String { card.ask.system[key(lang)]! }
    /// The card's answer-language key of an interface language.
    nonisolated static func key(_ lang: AppLanguage) -> String { lang == .zhHans ? "zh-Hans" : lang == .zhHant ? "zh-Hant" : "en" }
}

/// The reader model and tokenizer Ask keeps while the sheet is open (background threads only).
final class AskHolder: @unchecked Sendable {
    private let lock = NSLock()
    #if VOX_REAL_READER
    private var engine: MfaEngine?, tok: SpTokenizer?, session: MfaSession?, modelId: String?
    func tokenizer() throws -> SpTokenizer {
        if let tok { return tok }
        let t = try SpTokenizer(path: Storage.modelsRoot.appendingPathComponent(modelId ?? Prefs.reader.id).appendingPathComponent("Section1_SP_Tokenizer.spiece").path)
        tok = t; return t
    }
    func open() throws -> MfaSession {
        lock.lock(); defer { lock.unlock() }
        if let session, let modelId, modelId == Prefs.reader.id || ReaderModel.byId(Prefs.reader.id).fallback == modelId { return session }
        let m = Prefs.readerForLoad()
        closeLocked()
        guard Storage.ready(m) else { throw ReaderError(description: L("ask_no_model")) }
        let dir = Storage.modelsRoot.appendingPathComponent(m.id).path
        let t0 = Date()
        let e = try MfaEngine(dir: dir, ctx: m.budget.ctxBudget, threads: Prefs.effectiveThreads, weightCache: dir + "/" + ModelStore.weightCache, stopIds: m.template.stopIds)
        StatusLog.add(String(format: "reader loaded for Ask in %.1f s", Date().timeIntervalSince(t0)))
        let s = MfaSession(engine: e, tok: try tokenizer(), template: m.template)
        engine = e; session = s; modelId = m.id
        return s
    }
    func cancel() { session?.cancel() }
    func reset() { session?.reset(); session?.resume() }
    private func closeLocked() { session = nil; modelId = nil; engine = nil; tok = nil }
    func close() { lock.lock(); closeLocked(); lock.unlock() }
    private func count(_ text: String) -> Int { ((try? tokenizer())?.encode(text).count) ?? text.count / 3 }
    func countTokens(_ text: String) -> Int { count(text) }
    #else
    func open() throws -> StubLlm { throw ReaderError(description: "reader not built") }
    func cancel() {}
    func reset() {}
    func close() {}
    private func count(_ text: String) -> Int { text.count / 3 }
    func countTokens(_ text: String) -> Int { count(text) }
    #endif

    /// Hybrid retrieval: dense (when the embedder is there) and keyword passages, meeting routing
    /// (AskSelect.routeMeetings), AskEngine.fuse, then AskSelect.select with AskEngine.retrieval.
    func retrieve(_ sessions: [Session], _ question: String) -> [AskEngine.Excerpt] {
        let p = AskEngine.retrieval
        let depth = p.routeMeetings > 0 ? max(p.perMeeting, p.routeDepth) : p.perMeeting
        let qv = EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(question)) }) ?? nil
        let dense = qv.map { LibrarySearch.dense(sessions, qv: $0, maxPassages: depth, snippetChars: 1200) } ?? []
        let keyword = LibrarySearch.keyword(sessions, query: question, maxPassages: depth, snippetChars: 1200)
        // "What did Mark say": the meetings Mark is linked in are routed first, and his lines get the note bonus
        // (retrieval only: the excerpts already carry "Mark:", the prompt is unchanged).
        let personMeetings = PeopleStore.meetingsNaming(question, sessions)
        let routed = AskSelect.routeMeetings(
            dense: dense.map { h in (h.session.id.uuidString, h.passages.filter { $0.kind != .meeting }.map(\.score)) },
            keyword: keyword.map { h in (h.session.id.uuidString, h.passages.map(\.score)) }, p.routeMeetings, people: Set(personMeetings.keys))
        // The other meetings keep perMeeting candidates per list (the passages are in score order).
        func trim(_ hs: [LibrarySearch.Hit]) -> [LibrarySearch.Hit] {
            hs.map { h in routed.contains(h.session.id.uuidString) ? h : LibrarySearch.Hit(session: h.session, score: h.score, passages: Array(h.passages.prefix(p.perMeeting))) }
        }
        let fused = AskEngine.fuse(trim(dense), trim(keyword), k: p.rrfK) { id, sid in sid.map { personMeetings[id.uuidString]?.contains($0) == true } ?? false }
        let ranked = fused.filter { routed.contains($0.session.id.uuidString) } + fused.filter { !routed.contains($0.session.id.uuidString) }
        let byId = Dictionary(sessions.map { ($0.id.uuidString, $0) }, uniquingKeysWith: { a, _ in a })
        let items = ranked.map { AskSelect.Item(meeting: $0.session.id.uuidString, sec: $0.sec, at: $0.at, text: $0.text, score: $0.score, a: $0.a, b: $0.b) }
        let card = Prefs.reader.card
        func speak(_ x: AskSelect.Item) -> String { byId[x.meeting].map { Self.speakerText($0, x.a, x.b, x.text, card) } ?? x.text }
        let chosen = AskSelect.select(items, p, tokens: { self.count($0) }, src: AskTranscripts(byId: byId), prompt: speak)
        return chosen.compactMap { x in
            byId[x.meeting].map { AskEngine.Excerpt(session: $0, sec: x.sec, text: x.text, score: x.score, at: x.at, a: x.a, b: x.b, prompt: speak(x)) }
        }
    }

    /// An excerpt's text as the model reads it when the card asks for speakers (Android AskEngine.speakerText): a
    /// transcript excerpt line by line, each utterance with its speaker as the meeting shows it (the user's name, else
    /// "語者 N" / "Speaker N" in the interface language); a note's S-numbers resolved the same way.
    nonisolated static func speakerText(_ s: Session, _ a: Int, _ b: Int, _ text: String, _ card: ModelCard) -> String {
        guard card.ask.speakerLine != nil else { return text }
        if a < 0 { return SpeakerRefs.resolve(text, label: { s.name($0) }, known: Set(s.lines.map(\.speaker))) }
        guard s.lines.indices.contains(a) else { return text }
        // One line per speaker turn: consecutive utterances of the same speaker are joined under one label.
        var out: [String] = [], cur: Int? = nil, buf = ""
        for l in s.lines[a...min(max(b, a), s.lines.count - 1)] {
            if !buf.isEmpty && l.speaker != cur { out.append(card.ask.line(s.name(cur!), buf)); buf = "" }
            buf += (buf.isEmpty ? "" : " ") + l.text; cur = l.speaker
        }
        if !buf.isEmpty, let c = cur { out.append(card.ask.line(s.name(c), buf)) }
        return out.joined(separator: "\n")
    }
}

extension AskHolder {
    /// The in-meeting retrieval (Android AskEngine.retrieveIn): dense (when the meeting is indexed and the embedder is
    /// there) and keyword passages of `s` only, routeDepth candidates per list (what a routed meeting gets), no routing,
    /// AskEngine.fuse, then AskSelect.select. A meeting not indexed yet is cut on the fly for the keyword half.
    func retrieveIn(_ s: Session, _ question: String) -> [AskEngine.Excerpt] {
        let p = AskEngine.retrieval
        let depth = max(p.perMeeting, p.routeDepth)
        let indexed = LibrarySearch.index(s.id) != nil
        // Not indexed yet: the keyword half only, and the index is asked for meanwhile (Android parity).
        if !indexed, EmbedHost.ready, Dev.env["VOX_NOINDEX"] == nil { DispatchQueue.main.async { LibraryIndexer.shared.request(s) } }
        let qv = indexed ? (EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(question)) }) ?? nil) : nil
        let dense = qv.map { LibrarySearch.dense([s], qv: $0, maxPassages: depth, snippetChars: 1200) } ?? []
        let keyword = LibrarySearch.keyword([s], query: question, maxPassages: depth, snippetChars: 1200, unindexed: true)
        StatusLog.add("ask this meeting: indexed \(indexed), dense \(dense.flatMap(\.passages).count), keyword \(keyword.flatMap(\.passages).count)")
        let speakers = PeopleStore.meetingsNaming(question, [s])[s.id.uuidString] ?? []
        let ranked = AskEngine.fuse(dense, keyword, k: p.rrfK) { _, sid in sid.map { speakers.contains($0) } ?? false }
        let id = s.id.uuidString
        let items = ranked.map { AskSelect.Item(meeting: id, sec: $0.sec, at: $0.at, text: $0.text, score: $0.score, a: $0.a, b: $0.b) }
        let card = Prefs.reader.card
        func speak(_ x: AskSelect.Item) -> String { Self.speakerText(s, x.a, x.b, x.text, card) }
        let chosen = AskSelect.select(items, p, tokens: { self.countTokens($0) }, src: AskTranscripts(byId: [id: s], unindexed: true), prompt: speak)
        return chosen.map { AskEngine.Excerpt(session: s, sec: $0.sec, text: $0.text, score: $0.score, at: $0.at, a: $0.a, b: $0.b, prompt: speak($0)) }
    }
}

/// The library's transcripts as AskSelect sees them: a meeting's indexed chunks and the text of its utterances.
/// `unindexed` (the in-meeting Ask): a meeting with no index is chunked on the fly.
private struct AskTranscripts: AskSelect.Transcripts {
    let byId: [String: Session]
    var unindexed = false
    func chunks(_ meeting: String) -> [ClosedRange<Int>] {
        guard let s = byId[meeting], let vs = LibrarySearch.index(s.id)?.vectors ?? (unindexed ? LibrarySearch.unindexedVectors(s) : nil) else { return [] }
        return vs.filter { $0.kind == .chunk }.map { Int($0.a)...Int($0.b) }.sorted { $0.lowerBound < $1.lowerBound }
    }
    func span(_ meeting: String, _ a: Int, _ b: Int) -> AskSelect.Item? {
        guard let s = byId[meeting], s.lines.indices.contains(a), a <= b else { return nil }
        let b = min(b, s.lines.count - 1)
        return AskSelect.Item(meeting: meeting, sec: Int(s.lines[a].start), at: s.lines[a].start,
                              text: s.lines[a...b].map(\.text).joined(separator: " "), score: 0, a: a, b: b)
    }
}

/// The Ask sheet: question, streamed answer with citation links, sources, follow-up.
struct AskSheet: View {
    @ObservedObject var m: Model
    @ObservedObject var ask = AskEngine.shared
    let initial: String
    /// Opens a meeting at a time (the citation tapped).
    let open: (Session, Double) -> Void
    /// The in-meeting Ask: questions over this meeting only, its earlier questions (AskHistory) shown above.
    var meeting: Session? = nil
    @State private var followup = ""
    @State private var history: [AskEngine.Turn] = []
    @State private var confirmClear = false
    /// The run was working at the previous phase change: its end is the moment to confirm by touch.
    @State private var askWasWorking = false
    /// The composer is the sheet's reason for existing: it takes focus when the sheet settles.
    @FocusState private var composer: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if !history.isEmpty { Text(L("ask_meeting_earlier")).font(.caption.weight(.semibold)).foregroundStyle(.secondary) }
                        ForEach(history + ask.turns) { t in
                            if !history.isEmpty, t.id == ask.turns.first?.id { Divider() }
                            Text(ScreenScript.gen(t.question)).font(.headline).frame(maxWidth: .infinity, alignment: .trailing).multilineTextAlignment(.trailing)
                            VStack(alignment: .leading, spacing: 8) {
                                if t.answer.isEmpty && !t.done { phaseRow } else {
                                    if !t.done { phaseRow }
                                    Text(Self.linked(t.answer, t.pool, withTitle: meeting == nil)).font(.body).textSelection(.enabled)
                                        .environment(\.openURL, OpenURLAction { u in
                                            if u.scheme == "voxask", let s = t.pool.first(where: { $0.session.id.uuidString == u.host?.uppercased() }), let sec = Int(u.path.dropFirst()) { open(s.session, t.pool.first(where: { $0.session.id == s.session.id && $0.sec == sec })?.at ?? Double(sec)); return .handled }
                                            return .systemAction })
                                }
                                if !t.excerpts.isEmpty && t.done {
                                    Text(L("ask_sources")).font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 4)
                                    // By position: a note and a transcript passage at the same second share the id
                                    // (session@sec), and SwiftUI then drew the first one twice.
                                    ForEach(Array(t.excerpts.enumerated()), id: \.offset) { _, x in
                                        Button { open(x.session, x.at) } label: {
                                            HStack(alignment: .top, spacing: 6) {
                                                Text(ReaderProtocol.formatTs(x.sec)).font(Vox.fontPill.monospacedDigit()).foregroundStyle(Vox.accent).padding(.horizontal, 6).padding(.vertical, 2).background(Vox.accent.opacity(0.12), in: Capsule())
                                                Text(meeting != nil ? Self.named(x) : ScreenScript.gen(x.session.title) + " — " + Self.named(x)).font(.caption).lineLimit(2).multilineTextAlignment(.leading)
                                            }
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Vox.raised, in: RoundedRectangle(cornerRadius: Vox.radiusGroup)).overlay(RoundedRectangle(cornerRadius: Vox.radiusGroup).stroke(Vox.hairline)).id(t.id)
                        }
                        if case .failed(let e) = ask.phase { Text(L("ask_failed", e)).font(.footnote).foregroundStyle(.red) }
                        if ask.phase == .busy { Text(L("ask_busy")).font(.footnote).foregroundStyle(.secondary) }
                        Text(L("ask_disclaimer")).font(.caption2).foregroundStyle(.secondary)
                    }.padding(16)
                }
                .onChange(of: ask.turns.last?.answer) { _, _ in if let id = ask.turns.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
                // The answer arrived: confirm it by touch (an answer takes tens of seconds — the last line is the signal).
                // Only a run that was working counts: opening a sheet with earlier questions is silent.
                .onChange(of: ask.phase) { _, p in
                    defer { askWasWorking = p.working }
                    if askWasWorking && !p.working, let t = ask.turns.last, t.done, !t.answer.isEmpty { Haptics.success() }
                }
            }
            .scrollDismissesKeyboard(.interactively)   // dragging the answers down drops the keyboard; tap the field to ask again
            .background(Vox.bg)
            .safeAreaInset(edge: .bottom) {
                HStack {
                    TextField(L(!ask.turns.isEmpty ? "ask_followup_hint" : meeting != nil ? "ask_meeting_hint" : "ask_open_hint"), text: $followup).focused($composer).submitLabel(.send).onSubmit(send)
                    // Hides the keyboard (a .keyboard toolbar never showed for this field on iOS 26: it sits in a safeAreaInset).
                    if composer { Button { composer = false } label: { Image(systemName: "keyboard.chevron.compact.down").tapTarget() }.accessibilityLabel(L("done")) }
                    if ask.phase.working {
                        Button(L("ask_cancel")) { ask.cancel() }
                    } else {
                        Button(action: send) { Image(systemName: "paperplane.fill").tapTarget() }.disabled(followup.trimmingCharacters(in: .whitespaces).isEmpty).accessibilityLabel(L("ask_cd"))
                    }
                }
                // Redesign: the composer pill.
                .padding(.leading, 16).padding(.trailing, 6).frame(minHeight: 48)
                .background(Vox.fill, in: Capsule())
                .padding(.horizontal, 12).padding(.vertical, 8).background(Vox.bg)
            }
            // The principal VStack below is the bar's title (title + subtitle); a navigationTitle under it is dead.
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { VStack {
                    Text(L(meeting != nil ? "ask_meeting_title" : "ask_title")).font(.headline)
                    Text(meeting.map { ScreenScript.gen($0.title) + " · " + L("ask_experimental") } ?? L("ask_experimental")).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                } }
                ToolbarItem(placement: .cancellationAction) { Button(L("done")) { dismiss() } }
                // In-meeting: a new conversation also forgets this meeting's earlier questions.
                // Saved questions are lost for good, so a meeting with any asks first; nothing to clear → disabled.
                ToolbarItem(placement: .primaryAction) { Button { if meeting != nil, !(history.isEmpty && ask.turns.isEmpty) { confirmClear = true } else { ask.reset() } } label: { Image(systemName: "arrow.counterclockwise") }
                    .disabled(history.isEmpty && ask.turns.isEmpty)
                    .accessibilityLabel(L(meeting != nil ? "ask_meeting_clear" : "ask_reset"))
                    .confirmationDialog(L("ask_meeting_clear"), isPresented: $confirmClear, titleVisibility: .visible) {
                        Button(L("ask_meeting_clear"), role: .destructive) { ask.reset(); if let m = meeting { history = []; AskHistory.clear(m.id) } }
                        Button(L("cancel"), role: .cancel) {}
                    } }
            }
        }
        .onAppear {
            if let mt = meeting {
                history = AskHistory.load(mt.id).map { i in
                    let xs = i.x.filter { $0.count == 2 }.map { AskEngine.Excerpt(session: mt, sec: Int($0[0]), text: "", score: 0, at: $0[1]) }
                    return AskEngine.Turn(question: i.q, answer: i.a, excerpts: xs, done: true, pool: xs)
                }
            }
            if ask.turns.isEmpty, !initial.isEmpty { ask.ask(m.library.sessions, question: initial, meeting: meeting) }
            else if meeting != nil, initial.isEmpty { SessionView.preloadEmbedder() }
            // The keyboard with the sheet: typing is the reason this sheet opens, and the composer lives in a
            // safe-area inset so the keyboard lifts it. 0.4 s lets the sheet finish presenting first.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { composer = true }
        }
        .task {
            // DEV: VOX_ASK_CLEAR=<seconds> taps ↺ that long after the sheet opens (history checks without UI automation).
            if let m = meeting, let c = Dev.env["VOX_ASK_CLEAR"].flatMap(Double.init) {
                try? await Task.sleep(nanoseconds: UInt64(c * 1e9)); ask.reset(); history = []; AskHistory.clear(m.id)
                StatusLog.add("ask history cleared: \(AskHistory.load(m.id).count) left"); return
            }
            // DEV (README animation): VOX_ASK_OPEN=<seconds> taps the source at that second once the answer is in.
            guard let sec = Dev.env["VOX_ASK_OPEN"].flatMap(Int.init) else { return }
            for _ in 0..<240 {
                if let t = ask.turns.last, t.done, let x = t.excerpts.first(where: { $0.sec == sec }) {
                    try? await Task.sleep(nanoseconds: 3_000_000_000); open(x.session, x.at); return
                }
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
        .onDisappear { ask.release() }
    }
    /// The stage and the seconds since the question: a 20 s wait with a bare spinner looked hung.
    private var phaseRow: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
            let secs = AskEngine.elapsedSec(ask.startedAt, ctx.date)
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Group {
                    switch ask.phase {
                    case .retrieving: Text(L(meeting != nil ? "ask_meeting_stage_searching" : "ask_stage_searching", secs))
                    case .reading(let n): Text(L("ask_stage_reading", n, secs))
                    default: Text(L("ask_stage_answering", secs))
                    }
                }.font(.subheadline).foregroundStyle(.secondary).monospacedDigit()
            }
        }
    }
    private func send() {
        let q = followup.trimmingCharacters(in: .whitespacesAndNewlines); guard !q.isEmpty else { return }
        followup = ""; ask.ask(m.library.sessions, question: q, meeting: meeting)
    }
    /// An excerpt as listed under the sources: its S-numbers (a note passage) named as its meeting names them (display only).
    static func named(_ x: AskEngine.Excerpt) -> String {
        let s = x.session, known = Set(s.lines.map(\.speaker))
        // Quoted text in the app's script (display only; the model read the stored text).
        return SpeakerRefs.resolve(ScreenScript.said(x.text), label: { ScreenScript.name(s, $0) }, known: known.isEmpty ? nil : known)
    }
    /// `[[id@sec]]` → "title · m:ss" links (voxask://id/sec), checked against the excerpts.
    /// `withTitle`: the meeting's title in the link; the in-meeting Ask shows the time alone.
    static func linked(_ text: String, _ xs: [AskEngine.Excerpt], withTitle: Bool = true) -> AttributedString {
        // A tag still being written (streaming) or cut by the token cap is hidden, not shown raw.
        // A saved answer may be in the other script (asked before a language switch): shown in the app's script.
        let cleaned = ScreenScript.gen(text).replacingOccurrences(of: #"\[\[[^\]\n]*$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\[\[[^@\]\n]*\]\]"#, with: "", options: .regularExpression)   // `[[no excerpt]]`
            .replacingOccurrences(of: #"(?m)^[ \t]*[*-][ \t]+"#, with: "• ", options: .regularExpression)   // Markdown bullets, shown as text
        let rx = try! NSRegularExpression(pattern: #"\[\[([0-9A-Fa-f-]{36})@(\d+)\]\]"#)
        // The model names speakers by its S-numbers (from the notes): each as the cited meeting names it (display only).
        let text = SpeakerRefs.resolveCited(cleaned, cite: rx) { mt in
            let ns = cleaned as NSString, id = ns.substring(with: mt.range(at: 1)).uppercased()
            guard let s = xs.first(where: { $0.session.id.uuidString == id })?.session else { return nil }
            let known = Set(s.lines.map(\.speaker))
            return SpeakerRefs.Labels(label: { ScreenScript.name(s, $0) }, known: known.isEmpty ? nil : known)
        }
        var out = AttributedString()
        let ns = text as NSString
        var last = 0
        for mt in rx.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += AttributedString(ns.substring(with: NSRange(location: last, length: mt.range.location - last)))
            let id = ns.substring(with: mt.range(at: 1)).uppercased(), sec = Int(ns.substring(with: mt.range(at: 2))) ?? 0
            // Checked against the excerpts of this conversation (Android AskEngine.cite): the exact tag, else the nearest excerpt
            // of that meeting (a made-up second), else dropped.
            let same = xs.filter { $0.session.id.uuidString == id }
            if let x = same.first(where: { $0.sec == sec }) ?? same.min(by: { abs($0.sec - sec) < abs($1.sec - sec) }) {
                // Redesign: the summary's citation pill (text on its 12 % tint, thin no-break spaces as padding).
                var a = AttributedString("\u{202F}" + (withTitle ? ScreenScript.gen(x.session.title) + " · " + ReaderProtocol.formatTs(x.sec) : ReaderProtocol.formatTs(x.sec)) + "\u{202F}")
                a[Attr.Link.self] = URL(string: "voxask://\(id)/\(x.sec)"); a[Attr.Foreground.self] = Vox.accent   // Attr: Sendable-safe keys
                a[Attr.Background.self] = Vox.accent.opacity(0.12); a[Attr.Font.self] = .caption.weight(.semibold)
                out += a
            }
            last = mt.range.location + mt.range.length
        }
        out += AttributedString(ns.substring(from: last))
        return out
    }
}

/// Chinese amounts of a thousand or more written in digits ("八萬二" → "82,000"), for the excerpts of a
/// non-Chinese Ask (Android ChineseAmounts): the small reader translated 八萬 as "eight thousand" and no
/// prompt wording fixed it, whereas digits are simply copied. Spoken shortcuts are read as said:
/// 八萬二 = 82,000, 四千五 = 4,500, 一萬零五 = 10,005. A run starts with a digit (千萬不要, 萬一 are words).
enum ChineseAmounts {
    private static let run = try! NSRegularExpression(pattern: "[0-9]+(?:\\.[0-9]+)?[千萬万億亿]+|[一二兩两三四五六七八九十][零〇一二兩两三四五六七八九十百千萬万億亿]*")

    static func toDigits(_ text: String) -> String {
        let ns = text as NSString
        var out = "", last = 0
        for m in run.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let s = ns.substring(with: m.range)
            if let v = value(s), v >= 1000 { out += format(v) } else { out += s }
            last = m.range.location + m.range.length
        }
        return out + ns.substring(from: last)
    }

    static func format(_ v: Int64) -> String {
        var s = String(v), r = ""
        while s.count > 3 { r = "," + s.suffix(3) + r; s.removeLast(3) }
        return s + r
    }

    private static func digit(_ c: Character) -> Int64? {
        switch c { case "零", "〇": return 0; case "一": return 1; case "二", "兩", "两": return 2; case "三": return 3; case "四": return 4
        case "五": return 5; case "六": return 6; case "七": return 7; case "八": return 8; case "九": return 9; default: return nil }
    }
    private static func unit(_ c: Character) -> Int64? {
        switch c { case "十": return 10; case "百": return 100; case "千": return 1000; case "萬", "万": return 10_000; case "億", "亿": return 100_000_000; default: return nil }
    }

    /// The run's value, or nil when it is not a well-formed number.
    static func value(_ s: String) -> Int64? {
        let cs = Array(s)
        guard let f = cs.first else { return nil }
        if f.isASCII, f.isNumber {
            let num = String(cs.prefix { ($0.isASCII && $0.isNumber) || $0 == "." })
            guard var n = Double(num) else { return nil }
            for c in cs.dropFirst(num.count) { guard let u = unit(c) else { return nil }; n *= Double(u) }
            return Int64(n.rounded())
        }
        var total: Int64 = 0, section: Int64 = 0, number: Int64 = -1, lastUnit: Int64 = 0
        for c in cs {
            if let d = digit(c) {
                if number > 0 && d != 0 { return nil }   // 二三: not a number
                if d != 0 { number = d }
                continue
            }
            guard let u = unit(c) else { return nil }
            if u >= 10_000 {
                // 萬 scales what is below it; 億 scales everything so far (一億二千萬 = 1.2 億).
                let g = (u > 10_000 ? total : 0) + section + max(number, 0)
                if g == 0 { return nil }
                total = (u > 10_000 ? 0 : total) + g * u; section = 0
            } else {
                section += (number > 0 ? number : (u == 10 ? 1 : -1)) * u
                if section < 0 { return nil }
            }
            number = -1; lastUnit = u
        }
        if number > 0 {
            // A bare digit right after a unit is the next unit down (八萬二, 四千五, 二十五); after 零 it is ones.
            let afterUnit = cs.count >= 2 && unit(cs[cs.count - 2]) != nil
            section += afterUnit ? number * max(lastUnit / 10, 1) : number
        }
        return total + section
    }
}
