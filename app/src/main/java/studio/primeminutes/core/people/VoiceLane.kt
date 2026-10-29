package studio.primeminutes.core.people

/**
 * Where a meeting's voice vectors stand in the voice lane, and the note the rename sheet shows for it (iOS VoiceLane).
 * The note never says "Comparing voices…" unless the lane is computing that meeting: a meeting that waits says what it
 * waits for, a failure offers Retry, a missing model offers Download.
 */
object VoiceLane {
    enum class Wait { TRANSCRIPTION, BATTERY, QUEUE }

    sealed interface Status {
        data class Queued(val wait: Wait) : Status
        data object Computing : Status
        data object Failed : Status
        data object ModelMissing : Status
    }

    enum class Note { SHORT, NONE_YET, NO_MATCH, COMPUTING, WAIT_TRANSCRIPTION, WAIT_BATTERY, WAIT_QUEUE, FAILED, MODEL_MISSING }

    /**
     * Null when the lane may compute a meeting now, else what it waits for. The meeting on screen ([front]) is computed
     * even while a transcription, a reading or Ask runs: only its selected lines are decoded, a few seconds at low
     * priority. A backlog meeting waits for the app to be idle and the battery above the floor (or charging).
     */
    fun wait(front: Boolean, busy: Boolean, batteryOk: Boolean): Wait? = when {
        front -> null
        busy -> Wait.TRANSCRIPTION
        !batteryOk -> Wait.BATTERY
        else -> null
    }

    /**
     * The sheet's note. [code]: 0 ranked, 1 too little voice, 2 no vectors yet, 3 no voice learned yet; [empty]: no
     * suggestion; [status]: the lane's state for the meeting (null: not queued).
     */
    fun note(code: Int, empty: Boolean, status: Status?): Note? {
        when {
            status == Status.ModelMissing -> return Note.MODEL_MISSING
            status == Status.Failed && code == 2 -> return Note.FAILED
            status == Status.Computing && (code == 2 || empty) -> return Note.COMPUTING
        }
        return when (code) {
            1 -> Note.SHORT
            2 -> when (status) {
                is Status.Queued -> when (status.wait) {
                    Wait.TRANSCRIPTION -> Note.WAIT_TRANSCRIPTION
                    Wait.BATTERY -> Note.WAIT_BATTERY
                    Wait.QUEUE -> Note.WAIT_QUEUE
                }
                Status.Computing -> Note.COMPUTING
                Status.Failed -> Note.FAILED
                else -> Note.WAIT_QUEUE
            }
            3 -> Note.NONE_YET
            else -> if (empty) Note.NO_MATCH else null
        }
    }
}
