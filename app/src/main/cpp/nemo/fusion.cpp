// fusion.cpp - see fusion.h. Written for this repo.
#include "fusion.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <map>

namespace nemo {

std::vector<std::pair<size_t, size_t>> codepoints(const std::string& s) {
    std::vector<std::pair<size_t, size_t>> out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = (unsigned char)s[i];
        size_t len = 1;
        if (c >= 0xF0) len = 4;
        else if (c >= 0xE0) len = 3;
        else if (c >= 0xC0) len = 2;
        if (i + len > s.size()) len = 1;      // truncated tail: treat as one unit, never drop bytes
        out.emplace_back(i, len);
        i += len;
    }
    return out;
}

namespace {

// Does this codepoint sit inside a word? Latin and digits only: Chinese has no word boundaries to keep,
// and treating a CJK character as its own unit is what a human annotator does too.
bool word_char(const std::string& s, size_t off, size_t len) {
    if (len != 1) return false;
    const char c = s[off];
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '\'';
}

// Sentence-final punctuation, as UTF-8.
bool sentence_end(const std::string& cp) {
    return cp == "\u3002" || cp == "\uFF1F" || cp == "\uFF01" || cp == "?" || cp == "!";   // not ".": decimals and abbreviations
}

// A speaker change estimated 1-3 characters AFTER a sentence end is almost always the next speaker's
// first word left on the wrong side ("...想法？我覺 | 得地點..."): character timing is coarse at a turn
// change, the punctuation is not. Move that short tail to the next piece. The mirror case (a short
// "了。" opening the next piece) is NOT moved: it is indistinguishable from a real one-word reply ("好。").
//
// [lead_guard] (Fusion::set_line_split, research flag, off by default): a sentence end with nothing spoken
// before it in the piece is the PREVIOUS speaker's punctuation that the ASR emitted at the turn start
// (build_segments hands it back). The tail after it is this piece's own short reply ("。对对", "。啊不是"),
// not the next speaker's first word: moving it erases the piece, and with it a change the diarizer saw.
bool spoken_cp(const std::string& s, size_t off, size_t len);
void snap_to_sentence_end(std::vector<TaggedPiece>& pieces, bool lead_guard) {
    for (size_t k = 0; k + 1 < pieces.size(); k++) {
        TaggedPiece& a = pieces[k];
        TaggedPiece& b = pieces[k + 1];
        if (a.speaker == b.speaker || a.speaker.empty() || b.speaker.empty()) continue;
        const auto cps = codepoints(a.text);
        size_t cut = cps.size();
        for (size_t i = cps.size(); i-- > 0;) {
            if (sentence_end(a.text.substr(cps[i].first, cps[i].second))) { cut = i + 1; break; }
        }
        const size_t tail = cps.size() - cut;
        if (cut == cps.size() || tail == 0 || tail > 3 || cut == 0) continue;
        if (lead_guard) {
            bool before = false;
            for (size_t i = 0; i + 1 < cut && !before; i++) before = spoken_cp(a.text, cps[i].first, cps[i].second);
            if (!before) continue;
        }
        const size_t byte = cps[cut].first;
        b.text = a.text.substr(byte) + b.text;
        a.text.resize(byte);
    }
}

// Turn the per-character verdicts into pieces. Where words exist, the WORD is the unit of attribution:
// a boundary that would fall inside a word moves to the word's majority speaker instead.
//
// This is not cosmetic. Attribute at character granularity and a boundary that lands mid-word prints as
// "...subsequently dro" / "f" - the scorer then reads two tokens where the reference has one, and a pure
// placement error shows up as WER: the same audio scored 0.1765 with clean cuts and 0.3176 with cuts
// through words, with an identical transcript either way. Attribution accuracy barely moved between those
// two runs, which is how we know the damage was in the cut, not in the speaker choice.
std::vector<TaggedPiece> tag_sequence(const std::string& text, const std::vector<const Turn*>& mark,
                                      const std::vector<char>& snapped, bool lead_guard) {
    const auto cps = codepoints(text);
    std::vector<TaggedPiece> pieces;
    if (cps.empty()) return pieces;

    for (size_t i = 0; i < cps.size();) {
        size_t j = i + 1;
        if (word_char(text, cps[i].first, cps[i].second)) {
            while (j < cps.size() && word_char(text, cps[j].first, cps[j].second)) j++;
        }
        // majority over the word's characters; a tie keeps the first character's verdict
        std::map<std::pair<std::string, bool>, int> votes;
        for (size_t k = i; k < j; k++) {
            const std::string spk = mark[k] ? mark[k]->speaker : std::string();
            votes[{spk, snapped[k] && mark[k] != nullptr}]++;
        }
        auto best = votes.begin();
        for (auto it = votes.begin(); it != votes.end(); ++it) {
            if (it->second > best->second) best = it;
        }
        const std::string spk = best->first.first;
        const bool snap = best->first.second;

        if (!pieces.empty() && pieces.back().speaker == spk && pieces.back().snapped == snap) {
            TaggedPiece& p = pieces.back();
            for (size_t k = i; k < j; k++) p.text.append(text, cps[k].first, cps[k].second);
            for (size_t k = i; k < j; k++) {
                if (mark[k]) p.min_confidence = std::min(p.min_confidence, mark[k]->confidence);
            }
        } else {
            TaggedPiece p;
            p.speaker = spk;
            p.snapped = snap;
            p.min_confidence = 1.0f;
            for (size_t k = i; k < j; k++) {
                p.text.append(text, cps[k].first, cps[k].second);
                if (mark[k]) p.min_confidence = std::min(p.min_confidence, mark[k]->confidence);
            }
            pieces.push_back(std::move(p));
        }
        i = j;
    }
    snap_to_sentence_end(pieces, lead_guard);
    return pieces;
}

// A spoken character: not punctuation or space (same ranges as snap_to_turn_starts' `spoken`).
bool spoken_cp(const std::string& s, size_t off, size_t len) {
    const unsigned char* u = (const unsigned char*)s.data() + off;
    uint32_t cp = u[0];
    if (len == 2) cp = ((u[0] & 0x1F) << 6) | (u[1] & 0x3F);
    else if (len == 3) cp = ((u[0] & 0x0F) << 12) | ((u[1] & 0x3F) << 6) | (u[2] & 0x3F);
    else if (len == 4) cp = ((u[0] & 0x07) << 18) | ((u[1] & 0x3F) << 12) | ((u[2] & 0x3F) << 6) | (u[3] & 0x3F);
    if (cp < 0x80) return (bool)std::isalnum((int)cp);
    return !((cp >= 0x2000 && cp <= 0x206F) || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFF0F) ||
             (cp >= 0xFF1A && cp <= 0xFF20) || (cp >= 0xFF3B && cp <= 0xFF40) || (cp >= 0xFF5B && cp <= 0xFF65));
}

}  // namespace

void Fusion::update_turns(const std::vector<Turn>& snapshot) {
    // Match tolerance: two diar frames. A streaming turn is re-stated with the same start and a
    // later end; anything else (a new turn, a re-segmentation) is a new interval.
    const int64_t tol = rate_ / 100;
    for (const Turn& t : snapshot) {
        auto it = std::find_if(turns_.begin(), turns_.end(),
                               [&](const Turn& e) { return std::abs(e.start - t.start) <= tol; });
        if (it == turns_.end()) {
            turns_.push_back(t);
        } else {
            it->end = std::max(it->end, t.end);
            it->confidence = t.confidence;
            if (!t.speaker.empty()) it->speaker = t.speaker;
        }
    }
    std::sort(turns_.begin(), turns_.end(), [](const Turn& a, const Turn& b) { return a.start < b.start; });
}

const Turn* Fusion::covering(int64_t t, int64_t dur, bool* snapped) const {
    if (snapped) *snapped = false;
#ifdef NEMO_PLANT_FAULT
    // Test-fault hook: proves tests/selftest.sh can tell a working attributor from one that always
    // answers with the first turn. Compiled only with -DNEMO_PLANT_FAULT; the self-test asserts that
    // this build FAILS, so an assertion that never fires cannot pass as coverage.
    return turns_.empty() ? nullptr : &turns_.front();
#endif
    const Turn* best = nullptr;
    int64_t best_ov = 0;
    for (const Turn& e : turns_) {
        const int64_t lo = std::max<int64_t>(e.start, t);
        const int64_t hi = std::min<int64_t>(e.end, t + dur);
        const int64_t ov = hi - lo;
        if (ov > best_ov) { best_ov = ov; best = &e; }
    }
    if (best) return best;

    // No overlap: inside a between-turns gap. Snap to the nearer end of the closest turn rather than
    // inventing an unattributed island, but only for gaps short enough that a word really could not
    // have been spoken there.
    if (gap_fill_ == GapFill::PREVIOUS) {
        // The last turn that had ended by now; if the audio precedes every turn, the first one after it.
        const Turn* prev = nullptr;
        for (const Turn& e : turns_) {
            if (e.end <= t + dur && (!prev || e.end > prev->end)) prev = &e;
        }
        if (prev) { if (snapped) *snapped = true; return prev; }
        for (const Turn& e : turns_) if (!prev || e.start < prev->start) prev = &e;
        if (prev) { if (snapped) *snapped = true; return prev; }
        return nullptr;
    }
    const bool infinite = gap_snap_s_ <= 0.0;      // 0 = always nearest, never leave text untagged
    const int64_t snap = infinite ? (int64_t)1e18 : (int64_t)(gap_snap_s_ * rate_);
    int64_t best_d = snap + 1;
    for (const Turn& e : turns_) {
        const int64_t d = std::min(std::abs(t - e.end), std::abs(e.start - (t + dur)));
        if (d < best_d) { best_d = d; best = &e; }
    }
    if (best_d <= snap && best) { if (snapped) *snapped = true; return best; }
    return nullptr;
}

// Right-align a delta's characters inside the audio it describes, and append them to the timeline.
// A delta arrives when words CAME OUT, so its span also contains the silence that preceded them;
// spreading characters across the whole span would put the first characters of every turn in that
// silence, which is the single easiest way to mis-tag a speaker change.
void Fusion::push_delta(const std::string& delta, int64_t span_start, int64_t span_end) {
    append_placed(delta, span_start, span_end, false);
}

void Fusion::push_token(const std::string& text, int64_t at, int64_t end) {
    append_placed(text, at, end, true);
}

void Fusion::append_placed(const std::string& text, int64_t span_start, int64_t span_end, bool exact) {
    const auto cps = codepoints(text);
    if (cps.empty()) return;
    if (span_end <= span_start) span_end = span_start + 1;
    // exact: the token fills its interval. inferred: right-align `n * char_dur` inside the interval.
    const int64_t est = exact ? (span_end - span_start)
                              : std::min<int64_t>(span_end - span_start,
                                                  (int64_t)(cps.size() * char_dur_s_ * rate_));
    const int64_t base = span_end - est;
    const double step = double(est) / double(cps.size());
    for (size_t i = 0; i < cps.size(); i++) {
        const int64_t at = base + (int64_t)std::llround(i * step);
        const int64_t dur = std::max<int64_t>(1, (int64_t)std::llround(step));
        text_.append(text, cps[i].first, cps[i].second);
        spans_.push_back(CharSpan{at, at + dur});
    }
}

std::vector<TaggedPiece> Fusion::attribute_from(size_t char_from, size_t byte_from) const {
    const std::string tail = byte_from ? text_.substr(byte_from) : text_;
    const auto cps = codepoints(tail);
    std::vector<const Turn*> mark(cps.size(), nullptr);
    std::vector<char> snap(cps.size(), 0);      // vector<bool> has no addressable elements
    for (size_t i = 0; i < cps.size() && char_from + i < spans_.size(); i++) {
        bool sn = false;
        const CharSpan& sp = spans_[char_from + i];
        mark[i] = covering(sp.start, sp.end - sp.start, &sn);
        snap[i] = sn ? 1 : 0;
    }
    auto pieces = tag_sequence(tail, mark, snap, (line_split_ & 1) != 0);
    snap_to_turn_starts(pieces, tail, char_from);
    // Recover the time range each piece covers from the character spans (tag_sequence groups by word,
    // so times cannot travel with the text there).
    size_t pos = char_from;
    for (auto& p : pieces) {
        const size_t n = codepoints(p.text).size();
        if (pos < spans_.size()) p.start_s = double(spans_[pos].start) / rate_;
        for (size_t k = pos; k < pos + n && k < spans_.size(); k++) {
            p.end_s = std::max(p.end_s, double(spans_[k].end) / rate_);
        }
        pos += n;
    }
    return pieces;
}

// Energy refinement of a snap target - see Fusion::set_turn_energy. The search is capped by the move cap and
// by the characters the change is allowed to move between, so it is one pass over at most ~80 10 ms frames
// per clean change, and only around change points: the live path pays it for the tail it re-attributes, the
// final pass for the changes in the transcript. Nothing else in the attribution chain touches the curve.
bool Fusion::refine_target_to_energy(int64_t& target, int64_t t, int64_t lo_t, int64_t hi_t,
                                     int64_t max_shift) const {
    if (turn_energy_s_ <= 0 || !energy_) return false;
    const int64_t w = std::min(max_shift, (int64_t)(turn_energy_s_ * rate_));
    const int64_t w0 = std::max(lo_t, t - w), w1 = std::min(hi_t, t + w);
    if (w1 <= w0) return false;
    EnergyDip d;
    if (!energy_(w0, w1, &d)) return false;
    // The bar to beat is the curve at the shipped target: its own dip, over a 0.15 s neighbourhood. A dip
    // that is only marginally deeper than the target's silence is noise, and a target outside the search
    // window has no measured bar, so the shipped target wins there too.
    const int64_t n = (int64_t)(0.15 * rate_);
    const int64_t n0 = std::max(w0, target - n), n1 = std::min(w1, target + n);
    EnergyDip near;
    if (n1 <= n0 || !energy_(n0, n1, &near)) return false;
    if (d.val >= near.val - turn_energy_dip_) return false;
    target = d.at;
    return true;
}

void Fusion::snap_to_turn_starts(std::vector<TaggedPiece>& pieces, const std::string& text, size_t char_from) const {
    if (turn_snap_s_ <= 0 || pieces.size() < 2 || turns_.empty()) return;
    const auto cps = codepoints(text);
    const size_t n = cps.size();
    // Which piece each character belongs to; the pieces tile the text in order.
    std::vector<int> label(n, -1);
    std::vector<size_t> first(pieces.size() + 1, n);
    size_t c = 0;
    for (size_t k = 0; k < pieces.size(); k++) {
        first[k] = c;
        const size_t m = codepoints(pieces[k].text).size();
        for (size_t i = 0; i < m && c < n; i++) label[c++] = (int)k;
    }
    if (c != n) return;
    first[pieces.size()] = n;
    auto at = [&](size_t i) { return char_from + i < spans_.size() ? spans_[char_from + i].start : INT64_MAX; };
    auto word = [&](size_t i) { return word_char(text, cps[i].first, cps[i].second); };
    // A spoken character (not punctuation or space): each side of a moved change keeps at least one, else a
    // side left with only "？" is handed back whole by build_segments and two lines of one speaker merge
    // across the other's turn.
    auto spoken = [&](size_t i) {
        const unsigned char* u = (const unsigned char*)text.data() + cps[i].first;
        uint32_t cp = u[0];
        if (cps[i].second == 2) cp = ((u[0] & 0x1F) << 6) | (u[1] & 0x3F);
        else if (cps[i].second == 3) cp = ((u[0] & 0x0F) << 12) | ((u[1] & 0x3F) << 6) | (u[2] & 0x3F);
        else if (cps[i].second == 4) cp = ((u[0] & 0x07) << 18) | ((u[1] & 0x3F) << 12) | ((u[2] & 0x3F) << 6) | (u[3] & 0x3F);
        if (cp < 0x80) return (bool)std::isalnum((int)cp);
        return !((cp >= 0x2000 && cp <= 0x206F) || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFF0F) ||
                 (cp >= 0xFF1A && cp <= 0xFF20) || (cp >= 0xFF3B && cp <= 0xFF40) || (cp >= 0xFF5B && cp <= 0xFF65));
    };
    auto any_spoken = [&](size_t from, size_t to) {
        for (size_t j = from; j < to; j++) if (spoken(j)) return true;
        return false;
    };
    // Silence before character i: from the end of the last spoken character before it to its start.
    auto pause_before = [&](size_t i) -> int64_t {
        size_t j = i;
        while (j > 0 && !spoken(j - 1)) j--;
        if (j == 0 || char_from + j - 1 >= spans_.size()) return 0;
        size_t s = i;
        while (s < n && !spoken(s)) s++;
        return s < n ? at(s) - spans_[char_from + j - 1].end : 0;
    };
    // Longest silence strictly between the characters (a, b): the pause guard for a move - a boundary must
    // never cross a pause the text already sits in (the DER cost of the first version, docs/design/
    // speaker-boundary.md). The endpoints are excluded: the pause at the current change and the pause the
    // move aims at are the point of the move, not something it crosses.
    auto max_pause_between = [&](size_t a, size_t b) -> int64_t {
        int64_t m = 0;
        for (size_t j = a + 1; j < b; j++) m = std::max(m, pause_before(j));
        return m;
    };
    const int64_t pause = (int64_t)(turn_pause_s_ * rate_), max_shift = (int64_t)(turn_max_shift_s_ * rate_);
    const int64_t win = (int64_t)(turn_snap_s_ * rate_), pad = (int64_t)(turn_pad_s_ * rate_),
                  lead = (int64_t)(turn_lead_s_ * rate_);
    bool moved = false;
    size_t floor = 0;                                   // a later change never moves before an earlier one
    for (size_t k = 0; k + 1 < pieces.size(); k++) {
        const TaggedPiece& a = pieces[k];
        const TaggedPiece& b = pieces[k + 1];
        if (a.speaker.empty() || b.speaker.empty() || a.speaker == b.speaker) continue;
        size_t ka = k, kb = k + 1;                      // the runs of A's and B's pieces around the change
        while (ka > 0 && pieces[ka - 1].speaker == a.speaker) ka--;
        while (kb + 1 < pieces.size() && pieces[kb + 1].speaker == b.speaker) kb++;
        const size_t lo = std::max(first[ka] + 1, floor + 1), hi = first[kb + 1] - 1, pos = first[k + 1];
        if (lo > hi) continue;
        const int64_t t = at(pos);
        // B's own turn starting nearest to the change.
        const Turn* tb = nullptr;
        for (const Turn& e : turns_) {
            if (e.speaker != b.speaker || std::abs(e.start + lead - t) > win) continue;
            if (!tb || std::abs(e.start + lead - t) < std::abs(tb->start + lead - t)) tb = &e;
        }
        if (!tb) continue;
        // Someone else still talking when B starts (unpadded): overlap, leave the change where it is.
        const int64_t core = tb->start == 0 ? 0 : tb->start + pad;
        bool overlap = false;
        for (const Turn& e : turns_) {
            if (e.speaker == b.speaker) continue;
            const int64_t es = e.start == 0 ? 0 : e.start + pad, ee = e.end - pad;
            if (es < core && ee > core) { overlap = true; break; }
        }
        if (overlap) continue;
        // First character at or after the turn start, moved back to the start of a word it falls in.
        int64_t target = tb->start + lead;
        // Energy refinement: the deepest dip of the diarizer's 10 ms log-energy curve within the move cap
        // around the text's change. It replaces the turn-start target only when its dip is deeper by the
        // margin than the dip at the turn start, so a shallower dip next to a sharp turn start keeps the
        // shipped target, and ties go to the turn start.
        const bool by_energy = refine_target_to_energy(target, t, at(lo), at(hi), max_shift);
        size_t i = lo;
        while (i <= hi && at(i) < target) i++;
        if (i > hi) continue;
        while (i > lo && word(i - 1) && word(i)) i--;
        if (i == pos || (word(i - 1) && word(i))) continue;
        if (!any_spoken(std::max(first[ka], floor), i) || !any_spoken(i, first[kb + 1])) continue;
        // Moving only a space or punctuation changes nothing anyone said, but would stretch a line's time
        // span across the pause to the other speaker's first word.
        if (!any_spoken(std::min(i, pos), std::max(i, pos))) continue;
        // A pause right at the change is the strongest cue there is: keep it unless the turn start points at a
        // longer one (else the moved words drag a line's time span across the silence).
        if (std::abs(at(i) - t) > max_shift) continue;
        if (pause_before(pos) >= pause && pause_before(i) < pause_before(pos)) continue;
        // A dip on the other side of a pause the text already sits in is not the change: dragging a line
        // across the silence is exactly what the pause guard exists for.
        if (by_energy && max_pause_between(std::min(i, pos), std::max(i, pos)) >= pause) continue;
        for (size_t j = std::min(i, pos); j < std::max(i, pos); j++) label[j] = (int)(i < pos ? k + 1 : k);
        floor = i;
        moved = true;
    }
    if (!moved) return;
    std::vector<TaggedPiece> out;
    for (size_t i = 0; i < n;) {
        size_t j = i + 1;
        while (j < n && label[j] == label[i]) j++;
        TaggedPiece p = pieces[label[i]];
        p.text = text.substr(cps[i].first, (j < n ? cps[j].first : text.size()) - cps[i].first);
        out.push_back(std::move(p));
        i = j;
    }
    pieces = std::move(out);
}

std::vector<TaggedPiece> Fusion::on_delta(const std::string& delta, int64_t span_start, int64_t span_end) {
    Fusion tmp(*this);
    tmp.push_delta(delta, span_start, span_end);
    return tmp.attribute_all();
}

}  // namespace nemo
