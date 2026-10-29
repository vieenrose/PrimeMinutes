package studio.primeminutes.core.session

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.audio.Mp4Tags
import java.io.File

/**
 * Meetings shared between devices (manifest v2, [SessionShare]). The golden files in
 * `share_golden/` are byte-identical to `ios/tests/golden_share/` on the ios branch:
 *  - android_v2.m4a: written here (Mp4Tags + [SessionShare.forShare]), read by the iOS host tests;
 *  - ios_v2.m4a: written by iOS SessionFile (ios/tests/SessionShareTests.swift), read here;
 *  - android_v1.m4a / voxsum_v1.m4a: files from before v2 (the latter with the pre-rename VOXSUM tag);
 *  - future_v3.m4a: a newer schema major, which must be refused.
 * Regenerate the Android-written ones with `PM_SHARE_FIXTURES=<dir>` in the environment (see [regenerate]).
 */
class SessionShareTest {
    private fun golden(name: String): File =
        File(javaClass.classLoader!!.getResource("share_golden/$name")!!.toURI())

    companion object {
        const val SESSION_ID = "6F1C2B9E-3D4A-4C2B-9A55-0E7D2C1B8A01"
        const val CREATED = "2026-10-21T02:30:00Z"

        /** The meeting every fixture carries (zh-Hant): two speakers, one linked to a person. */
        fun meetingV1(): JSONObject = JSONObject().apply {
            put("primeminutes_version", 1)
            put("title", "預算會議")
            put("title_user_set", true)
            put("summary", "討論了明年的預算與時程。")
            put("action_items", "- 王小明：週五前寄出報價")
            put("notes", "#1 [0:00] (DECISION) 預算維持不變\n#2 [0:00] (ACTION) 王小明寄出報價")
            put("asr_model", "xasr-stream-q8-octav+sortformer-int8-f380")
            put("asr_backend", "nemo")
            put("llm_model", "gemma4-e2b-mobile-v1")
            put("edit_rev", 1761000000000L)
            put("speaker_names", JSONObject()
                .put("0", JSONObject().put("name", "王小明").put("confidence", "user").put("reason", "").put("person", "person-7f3a"))
                .put("1", JSONObject().put("name", "李華").put("confidence", "user").put("reason", "")))
            put("utterances", JSONArray()
                .put(JSONObject().put("index", 0).put("start", 0.0).put("end", 0.5).put("text", "我們來討論預算。").put("speaker", 0)
                    .put("token_times", JSONArray(listOf(0.0, 0.1, 0.2))))
                .put(JSONObject().put("index", 1).put("start", 0.5).put("end", 1.0).put("text", "時程沒有問題。").put("speaker", 1)))
        }

        private fun write(tone: File, dest: File, m: JSONObject) {
            check(Mp4Tags.write(tone, dest, SessionShare.encodeBlob(m), m.optString("title"), m.optString("summary"), null, "[00:00.00]x\n"))
        }

        /** Writes the Android-made fixtures into [dir] (PM_SHARE_FIXTURES=dir). */
        fun regenerate(tone: File, dir: File) {
            write(tone, File(dir, "android_v1.m4a"), meetingV1())
            write(tone, File(dir, "android_v2.m4a"), SessionShare.forShare(meetingV1(), SESSION_ID, SessionShare.parseIso(CREATED), "zh-Hant", true to false))
            write(tone, File(dir, "future_v3.m4a"), SessionShare.forShare(meetingV1(), SESSION_ID, null, null).put("primeminutes_version", 3).put("new_thing", JSONObject()))
        }
    }

    @Test fun regenerateWhenAsked() {
        val dir = System.getenv("PM_SHARE_FIXTURES") ?: return
        regenerate(golden("tone.m4a"), File(dir))
    }

    @Test fun allowlistDropsPeopleAndPrivateKeys() {
        val m = meetingV1().put("ask_history", JSONArray().put("q")).put("voiceprint", "x").put("cover_sig", "c")
        val out = SessionShare.forShare(m, SESSION_ID, 0L, "zh-Hant")
        val text = out.toString()
        assertFalse(text.contains("person"))
        assertFalse(text.contains("ask_history")); assertFalse(text.contains("voiceprint"))
        assertFalse(out.has("edit_rev")); assertFalse(out.has("cover_sig")); assertFalse(out.has("created_at"))
        assertEquals("王小明", out.getJSONObject("speaker_names").getJSONObject("0").getString("name"))
        assertEquals(2, out.getInt("primeminutes_version"))
        assertEquals(SESSION_ID, out.getString("session_id"))
        assertEquals(3, out.getJSONArray("utterances").getJSONObject(0).getJSONArray("token_times").length())
        assertTrue(out.getBoolean("title_user_set"))
        assertNull(SessionShare.userEdited(out))
        val ue = SessionShare.forShare(m.put("user_edited", JSONObject().put("summary", true).put("lines", true).put("x", 1)), SESSION_ID, null, null)
        assertEquals(true to true, SessionShare.userEdited(ue)); assertFalse(ue.getJSONObject("user_edited").has("x"))
    }

    @Test fun isoRoundTrip() {
        val ms = SessionShare.parseIso(CREATED)!!
        assertEquals(CREATED, SessionShare.iso(ms))
        assertEquals(1792549800000L, ms)
    }

    @Test fun gate() {
        assertEquals(SessionShare.Gate.OK, SessionShare.gate(JSONObject()))
        assertEquals(SessionShare.Gate.OK, SessionShare.gate(JSONObject().put("primeminutes_version", 2)))
        assertEquals(SessionShare.Gate.TOO_NEW, SessionShare.gate(JSONObject().put("primeminutes_version", 3)))
    }

    @Test fun scriptConversionTouchesEveryText() {
        val m = SessionShare.forShare(meetingV1(), SESSION_ID, null, "zh-Hant")
        val out = SessionShare.convertScript(m, { "G:$it" }, { "T:$it" })
        assertEquals("G:預算會議", out.getString("title"))
        assertEquals("G:王小明", out.getJSONObject("speaker_names").getJSONObject("0").getString("name"))
        assertEquals("T:我們來討論預算。", out.getJSONArray("utterances").getJSONObject(0).getString("text"))
        assertTrue(out.getString("notes").startsWith("G:"))
        assertEquals("預算會議", m.getString("title"))   // the input is not changed
    }

    private fun checkMeeting(m: JSONObject, from: String) {
        assertEquals(from, "預算會議", m.getString("title"))
        assertEquals(from, "討論了明年的預算與時程。", m.getString("summary"))
        assertEquals(from, 2, m.getJSONArray("utterances").length())
        val u0 = m.getJSONArray("utterances").getJSONObject(0)
        assertEquals(from, "我們來討論預算。", u0.getString("text")); assertEquals(from, 0, u0.getInt("speaker"))
        assertEquals(from, 3, u0.getJSONArray("token_times").length())
        assertEquals(from, "李華", m.getJSONObject("speaker_names").getJSONObject("1").getString("name"))
        assertTrue(from, m.optString("notes").contains("預算維持不變"))
        // Restores through the same parser as any session file.
        val l = PrimeMinutesSession.loaded(m, File("x"))
        assertEquals(from, 2, l.utterances.size)
        assertEquals(from, "王小明", l.speakerNames[0]?.name)
    }

    @Test fun androidV2Golden() {
        val m = SessionShare.readManifest(golden("android_v2.m4a"))!!
        checkMeeting(m, "android_v2")
        assertEquals(SESSION_ID, SessionShare.sessionId(m))
        assertEquals(SessionShare.parseIso(CREATED), SessionShare.createdAtMs(m))
        assertFalse(m.toString().contains("person"))
        assertEquals(true to false, SessionShare.userEdited(m))
        assertEquals(SessionShare.Gate.OK, SessionShare.gate(m))
    }

    @Test fun iosV2Golden() {
        val m = SessionShare.readManifest(golden("ios_v2.m4a"))!!
        checkMeeting(m, "ios_v2")
        assertEquals(SESSION_ID, SessionShare.sessionId(m)?.uppercase())
        assertEquals(SessionShare.parseIso(CREATED), SessionShare.createdAtMs(m))
        assertEquals("zh-Hant", m.getString("script"))
        assertFalse(m.toString().contains("person"))
        assertTrue(m.getBoolean("title_user_set"))
        assertEquals(true to false, SessionShare.userEdited(m))
        assertEquals(SessionShare.Gate.OK, SessionShare.gate(m))
    }

    @Test fun v1AndLegacyStillImport() {
        for (f in listOf("android_v1.m4a", "voxsum_v1.m4a")) {
            val m = SessionShare.readManifest(golden(f))
            assertNotNull(f, m)
            checkMeeting(m!!, f)
            assertNull(f, SessionShare.sessionId(m))
            assertEquals(f, SessionShare.Gate.OK, SessionShare.gate(m))
        }
    }

    @Test fun futureMajorRefused() {
        val m = SessionShare.readManifest(golden("future_v3.m4a"))!!
        assertEquals(SessionShare.Gate.TOO_NEW, SessionShare.gate(m))
    }

    @Test fun blobRoundTripAndBomb() {
        val m = meetingV1()
        assertEquals(m.toString(), SessionShare.decodeBlob(SessionShare.encodeBlob(m)).toString())
        assertNull(SessionShare.decodeBlob("not base64 !!"))
    }
}
