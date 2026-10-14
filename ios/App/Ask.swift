import SwiftUI
import Combine

/// Ask (RAG, experimental — Android AskEngine): a question over the library, answered by the reader model
/// from the passages the semantic index ranks highest; the passages carry `[[id@sec]]` tags the model
/// cites and the sheet turns into "title · m:ss" links.
@MainActor final class AskEngine: ObservableObject {
    static let shared = AskEngine()
    static let maxPassages = 8, passageTokens = 1500, answerTokens = 320, noteBonus: Float = 0.05

    enum Phase: Equatable { case idle, busy, retrieving, loading, answering, failed(String) }
    struct Excerpt: Identifiable { let session: Session, sec: Int, text: String, score: Float; var id: String { session.id.uuidString + "@\(sec)" } }
    struct Turn: Identifiable { let id = UUID(); let question: String; var answer: String; let excerpts: [Excerpt]; var done: Bool }

    @Published private(set) var phase = Phase.idle
    @Published private(set) var turns: [Turn] = []
    var busy: () -> Bool = { false }
    var recording: () -> Bool = { false }
    static var enabled: Bool { UserDefaults.standard.bool(forKey: "askEnabled") }
    /// Both models are on disk.
    static var available: Bool { EmbedHost.ready && Storage.ready(Prefs.reader) }

    private let holder = AskHolder()
    private var task: Task<Void, Never>?

    func ask(_ sessions: [Session], question: String) {
        task?.cancel()
        if busy() { phase = .busy; return }
        let lang = AppLanguage.current.resolved
        let first = turns.isEmpty
        task = Task { [holder] in
            phase = .retrieving
            let excerpts = await Task.detached(priority: .userInitiated) { holder.retrieve(sessions, question) }.value
            if Task.isCancelled { return }
            turns.append(Turn(question: question, answer: "", excerpts: excerpts, done: false))
            phase = .loading
            let isBusy = busy
            let result: Result<String, Error> = await Task.detached(priority: .userInitiated) {
                do {
                    let s = try holder.open()
                    s.resume()
                    await MainActor.run { AskEngine.shared.phase = .answering }
                    var prompt = first ? ReaderProtocol.p0 + Self.system(lang) + ReaderProtocol.p1 : ReaderProtocol.p3
                    prompt += Self.render(excerpts) + "\n\n" + question.trimmingCharacters(in: .whitespacesAndNewlines) + ReaderProtocol.p2
                    if s.append(s.tokenize(prompt, special: true)) < 0 {
                        s.reset()   // the conversation outgrew the cache: start over with this question only
                        let fresh = ReaderProtocol.p0 + Self.system(lang) + ReaderProtocol.p1 + Self.render(excerpts) + "\n\n" + question.trimmingCharacters(in: .whitespacesAndNewlines) + ReaderProtocol.p2
                        if s.append(s.tokenize(fresh, special: true)) < 0 { throw ReaderError(description: "prompt does not fit") }
                    }
                    var sb = ""
                    let t0 = Date()
                    _ = s.generateContinue(maxTokens: Self.answerTokens, stop: "", temp: ReaderProtocol.temp) { piece in
                        if Task.isCancelled { s.cancel() }
                        sb += piece
                        let now = sb
                        DispatchQueue.main.async { AskEngine.shared.publish(now, done: false) }
                    }
                    let st = s.lastStats; StatusLog.add(String(format: "ask answered in %.1f s: prefilled %d (reused %d) in %.1f s, generated %d in %.1f s; seq %d / ctx %d", Date().timeIntervalSince(t0), Int(st[0]), Int(st[1]), st[3], Int(st[2]), st[4], s.seqLength, s.context))
                    return .success(sb.trimmingCharacters(in: .whitespacesAndNewlines))
                } catch { return .failure(error) }
            }.value
            switch result {
            case .success(let a): publish(a, done: true); phase = isBusy() && a.isEmpty ? .busy : .idle
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
    func release() { cancel(); turns = []; phase = .idle; let h = holder; Task.detached { h.close() } }
    func reset() { cancel(); turns = []; phase = .idle; holder.reset() }

    nonisolated static func render(_ xs: [Excerpt]) -> String {
        if xs.isEmpty { return "(no excerpts)\n" }
        return xs.map { "[[\($0.session.id.uuidString)@\($0.sec)]] \($0.session.title) · \(ReaderProtocol.formatTs($0.sec))\n\($0.text.trimmingCharacters(in: .whitespacesAndNewlines))\n\n" }.joined()
    }
    nonisolated static func system(_ lang: AppLanguage) -> String {
        switch lang {
        case .zhHans: return "你是会议助理。只能根据下列会议摘录回答问题，不得加入摘录以外的信息。每个论点后面附上出处标签，格式与摘录开头完全相同：[[id@秒]]。若摘录中没有答案，就说「会议记录中没有提到」。回答请简短，使用简体中文。"
        case .zhHant: return "你是會議助理。只能根據下列會議摘錄回答問題，不得加入摘錄以外的資訊。每個論點後面附上出處標籤，格式與摘錄開頭完全相同：[[id@秒]]。若摘錄中沒有答案，就說「會議記錄中沒有提到」。回答請簡短，使用繁體中文。"
        default: return "You are a meeting assistant. Answer only from the meeting excerpts below; never add facts that are not in them. After each claim, cite its source tag exactly as it appears at the start of the excerpt: [[id@seconds]]. If the excerpts do not contain the answer, say that the meetings do not mention it. Be brief and answer in English."
        }
    }
}

/// The reader model and tokenizer Ask keeps while the sheet is open (background threads only).
final class AskHolder: @unchecked Sendable {
    private let lock = NSLock()
    #if VOX_REAL_READER
    private var engine: MfaEngine?, tok: SpTokenizer?, session: MfaSession?, modelId: String?
    func tokenizer() throws -> SpTokenizer {
        if let tok { return tok }
        let t = try SpTokenizer(path: Storage.modelsRoot.appendingPathComponent(Prefs.reader.id).appendingPathComponent("Section1_SP_Tokenizer.spiece").path)
        tok = t; return t
    }
    func open() throws -> MfaSession {
        lock.lock(); defer { lock.unlock() }
        let m = Prefs.reader
        if let session, modelId == m.id { return session }
        closeLocked()
        guard Storage.ready(m) else { throw ReaderError(description: L("ask_no_model")) }
        let dir = Storage.modelsRoot.appendingPathComponent(m.id).path
        let t0 = Date()
        let e = try MfaEngine(dir: dir, ctx: ReaderBudget.mobile.ctxBudget, threads: Prefs.effectiveThreads, weightCache: dir + "/weights.xnnpack_cache")
        StatusLog.add(String(format: "reader loaded for Ask in %.1f s", Date().timeIntervalSince(t0)))
        let s = MfaSession(engine: e, tok: try tokenizer())
        engine = e; session = s; modelId = m.id
        return s
    }
    func cancel() { session?.cancel() }
    func reset() { session?.reset(); session?.resume() }
    private func closeLocked() { session = nil; modelId = nil; engine = nil; tok = nil }
    func close() { lock.lock(); closeLocked(); lock.unlock() }
    private func count(_ text: String) -> Int { ((try? tokenizer())?.encode(text).count) ?? text.count / 3 }
    #else
    func open() throws -> ReaderLlm { throw ReaderError(description: "reader not built") }
    func cancel() {}
    func reset() {}
    func close() {}
    private func count(_ text: String) -> Int { text.count / 3 }
    #endif

    /// Dense retrieval: top passages across the library, notes first at equal score, ≤ passageTokens.
    func retrieve(_ sessions: [Session], _ question: String) -> [AskEngine.Excerpt] {
        guard let qv = EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(question)) }) ?? nil else { return [] }
        let hits = LibrarySearch.dense(sessions, qv: qv, maxPassages: 4, snippetChars: 1200)
        let all = hits.flatMap { h in h.passages.filter { $0.kind != .meeting }.map { p in
            AskEngine.Excerpt(session: h.session, sec: Int(p.timeSec), text: p.snippet, score: p.score + (p.kind == .note ? AskEngine.noteBonus : 0)) } }
            .sorted { $0.score > $1.score }
        var out: [AskEngine.Excerpt] = [], tokens = 0
        for x in all {
            if out.count >= AskEngine.maxPassages { break }
            let n = count(x.text) + 24
            if tokens + n > AskEngine.passageTokens { continue }
            tokens += n; out.append(x)
        }
        return out
    }
}

/// The Ask sheet: question, streamed answer with citation links, sources, follow-up.
struct AskSheet: View {
    @ObservedObject var m: Model
    @ObservedObject var ask = AskEngine.shared
    let initial: String
    /// Opens a meeting at a time (the citation tapped).
    let open: (Session, Double) -> Void
    @State private var followup = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(ask.turns) { t in
                            Text(t.question).font(.headline).frame(maxWidth: .infinity, alignment: .trailing).multilineTextAlignment(.trailing)
                            VStack(alignment: .leading, spacing: 8) {
                                if t.answer.isEmpty && !t.done { phaseRow } else {
                                    Text(Self.linked(t.answer, t.excerpts)).font(.body).textSelection(.enabled)
                                        .environment(\.openURL, OpenURLAction { u in
                                            if u.scheme == "voxask", let s = t.excerpts.first(where: { $0.session.id.uuidString == u.host?.uppercased() }), let sec = Double(u.path.dropFirst()) { open(s.session, sec); return .handled }
                                            return .systemAction })
                                }
                                if !t.excerpts.isEmpty && t.done {
                                    Text(L("ask_sources")).font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 4)
                                    ForEach(t.excerpts) { x in
                                        Button { open(x.session, Double(x.sec)) } label: {
                                            HStack(alignment: .top, spacing: 6) {
                                                Text(ReaderProtocol.formatTs(x.sec)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                                Text(x.session.title + " — " + x.text).font(.caption).lineLimit(2).multilineTextAlignment(.leading)
                                            }
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12)).id(t.id)
                        }
                        if case .failed(let e) = ask.phase { Text(L("ask_failed", e)).font(.footnote).foregroundStyle(.red) }
                        if ask.phase == .busy { Text(L("ask_busy")).font(.footnote).foregroundStyle(.secondary) }
                        Text(L("ask_disclaimer")).font(.caption2).foregroundStyle(.secondary)
                    }.padding(16)
                }
                .onChange(of: ask.turns.last?.answer) { _, _ in if let id = ask.turns.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
            }
            .background(Color(.systemGroupedBackground))
            .safeAreaInset(edge: .bottom) {
                HStack {
                    TextField(L("ask_followup_hint"), text: $followup).submitLabel(.send).onSubmit(send)
                    if ask.phase == .answering || ask.phase == .retrieving || ask.phase == .loading {
                        Button(L("ask_cancel")) { ask.cancel() }
                    } else {
                        Button(action: send) { Image(systemName: "paperplane.fill") }.disabled(followup.trimmingCharacters(in: .whitespaces).isEmpty).accessibilityLabel(L("ask_cd"))
                    }
                }.padding(12).background(.bar)
            }
            .navigationTitle(L("ask_title")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { VStack { Text(L("ask_title")).font(.headline); Text(L("ask_experimental")).font(.caption2).foregroundStyle(.secondary) } }
                ToolbarItem(placement: .cancellationAction) { Button(L("done")) { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button { ask.reset() } label: { Image(systemName: "arrow.counterclockwise") }.accessibilityLabel(L("ask_reset")) }
            }
        }
        .onAppear { if ask.turns.isEmpty, !initial.isEmpty { ask.ask(m.sessions, question: initial) } }
        .onDisappear { ask.release() }
    }
    private var phaseRow: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text(L(ask.phase == .retrieving ? "ask_retrieving" : ask.phase == .loading ? "ask_loading" : "ask_thinking")).font(.subheadline).foregroundStyle(.secondary)
        }
    }
    private func send() {
        let q = followup.trimmingCharacters(in: .whitespacesAndNewlines); guard !q.isEmpty else { return }
        followup = ""; ask.ask(m.sessions, question: q)
    }
    /// `[[id@sec]]` → "title · m:ss" links (voxask://id/sec); an unknown id is dropped.
    static func linked(_ text: String, _ xs: [AskEngine.Excerpt]) -> AttributedString {
        var out = AttributedString()
        let ns = text as NSString
        let rx = try! NSRegularExpression(pattern: #"\[\[([0-9A-Fa-f-]{36})@(\d+)\]\]"#)
        var last = 0
        for mt in rx.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += AttributedString(ns.substring(with: NSRange(location: last, length: mt.range.location - last)))
            let id = ns.substring(with: mt.range(at: 1)).uppercased(), sec = Int(ns.substring(with: mt.range(at: 2))) ?? 0
            if let x = xs.first(where: { $0.session.id.uuidString == id }) {
                var a = AttributedString(x.session.title + " · " + ReaderProtocol.formatTs(sec))
                a.link = URL(string: "voxask://\(id)/\(sec)"); a.foregroundColor = .accentColor
                out += a
            }
            last = mt.range.location + mt.range.length
        }
        out += AttributedString(ns.substring(from: last))
        return out
    }
}
