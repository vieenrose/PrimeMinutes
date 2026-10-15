package studio.primeminutes.core.models

/**
 * A model's chat template as literal pieces: what the model's own template renders around a system,
 * user and model turn (thinking off). It travels with the [LlmSpec], so weights with another template
 * (or a fine-tune with an adjusted protocol) need no change in the reader, Ask or the bench.
 */
data class ChatTemplate(
    /** Text of the BOS token; the tokenizer does not parse it, so it is mapped to [bosId]. */
    val bos: String,
    val bosId: Int,
    /** Open a system, user and model turn. */
    val system: String,
    val user: String,
    val model: String,
    /** Closes a turn (what the model writes at the end of its reply). */
    val end: String,
    /** Between [end] and the next turn. */
    val sep: String,
    /** Token ids that end a reply: the engine stops on them and they are left out of the kept sequence. */
    val stopIds: Set<Int>,
) {
    /** Before the system prompt. */
    val p0: String get() = bos + system
    /** System → user. */
    val p1: String get() = end + sep + user
    /** User → model. */
    val p2: String get() = end + sep + model
    /** Model → next user. */
    val p3: String get() = end + sep + user
    /** Opens a conversation that has no system turn. */
    val userFirst: String get() = bos + user

    companion object {
        /** Gemma 4, thinking off (note §4.1: what phone_live.py derives from the GGUF's template via
         *  /apply-template). The stop ids are `<eos>` (1), the metadata's third stop id (50) and `<turn|>` (106). */
        val GEMMA4 = ChatTemplate(
            bos = "<bos>", bosId = 2,
            system = "<|turn>system\n", user = "<|turn>user\n", model = "<|turn>model\n",
            end = "<turn|>", sep = "\n",
            stopIds = setOf(1, 50, 106),
        )
    }
}
