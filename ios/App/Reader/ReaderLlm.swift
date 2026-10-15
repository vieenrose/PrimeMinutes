import Foundation

/// The KV-keeping session the meeting reader drives (Android `ReaderLlm`): one growing
/// conversation, extended by `append` (prefill only) and `generateContinue` (its tokens join the
/// sequence), restarted by `reset`. Not thread-safe: the reader lane serializes every call.
protocol ReaderLlm: AnyObject {
    /// The model's chat template: every turn the reader writes is framed by its pieces.
    var template: ChatTemplate { get }
    /// Token ids of `text` without BOS; `special` parses template pieces (`<|turn>` …).
    func tokenize(_ text: String, special: Bool) -> [Int]
    /// Prefill `tokens` onto the cache. Returns the new sequence length, or -1 on failure.
    func append(_ tokens: [Int]) -> Int
    /// Decode until `stop` (kept in the sequence and at the end of the returned text), end of
    /// generation or `maxTokens`; `onToken` receives streamed pieces.
    func generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Void) -> String
    var seqLength: Int { get }
    func reset()
}

extension ReaderLlm {
    var template: ChatTemplate { ModelCard.default.template }
}

/// A model's chat template as literal pieces (Android `ChatTemplate`): what the model's own template
/// renders around a system, user and model turn (thinking off). It comes from the `ModelCard`, so
/// weights with another template (or a fine-tune with an adjusted protocol) need no change in the
/// reader, Ask or the summarizer.
struct ChatTemplate {
    /// Text of the BOS token; the tokenizer does not parse it, so it is mapped to `bosId`.
    let bos: String, bosId: Int
    /// Open a system, user and model turn.
    let system: String, user: String, model: String
    /// Closes a turn (what the model writes at the end of its reply), and what comes before the next turn.
    let end: String, sep: String
    /// Token ids that end a reply: the engine stops on them and they are left out of the kept sequence.
    let stopIds: Set<Int>

    /// Before the system prompt; system → user; user → model; model → next user.
    var p0: String { bos + system }
    var p1: String { end + sep + user }
    var p2: String { end + sep + model }
    var p3: String { end + sep + user }
    /// Opens a conversation that has no system turn.
    var userFirst: String { bos + user }

}

enum AgentState { case starting, listening, reading, restarting, summarizing, done }
enum DropReason { case parse, citation, cap, duplicate }

/// What the agent is doing, for the live Agent panel.
enum AgentEvent {
    case state(AgentState, window: Int = 0, ctxTokens: Int = 0, notes: Int = 0, windowMax: Int = 0, ctxMax: Int = 0)
    case fed(window: Int, what: String, tokens: Int, ms: Int, ctxTokens: Int)
    case turnToken(window: Int, piece: String)
    case turnDone(window: Int, reply: String, kept: Int, ms: Int)
    case noteKept(Note)
    case noteDropped(window: Int, line: String, reason: DropReason)
    case restart(count: Int, ctxBefore: Int, ctxAfter: Int)
}

struct ReaderError: Error, CustomStringConvertible { let description: String }
