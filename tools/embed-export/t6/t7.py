import json, sys, random, re, time
sys.path.insert(0, "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/emb"); import numpy as np, embed_lib as E
T = "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/emb/t6/"
items = json.load(open(T + "texts.json")); docs = [(i, x[2][20:]) for i, x in enumerate(items) if x[0] == "doc"]
# query -> target doc index (paraphrases, no shared keywords where possible)
Q = {
 "最近的物價和外食成本變化": 21, "高速公路哪裡塞車": 24, "為什麼平台不保護消費者": 26, "政府對直播帶貨的管制措施有哪些": 41,
 "今天節目的主題是什麼": 42, "直播購物為什麼這麼容易衝動消費": 59, "老人家把存款都花在打賞上的案例": 98, "週末的天氣預報": 80,
 "連假的車票還買得到嗎": 82, "怎樣才能真正保護消費者": 90, "直播產業的未來": 86, "數據和使用者參與如何形成正向循環": 33,
 "打賞門檻低的原因": 55, "娛樂和購物結合的影響": 59,
 "how do we reduce server load in the report renderer": 23, "when is code freeze for the launch": 25, "what did the OAuth fix change": 28,
 "results of the user testing of the setup checklist": 32, "problem with the Stripe webhook": 39, "who is responsible for onboarding, mobile and performance": 51,
 "churn rate went up, why": 74, "database keeps disconnecting on staging": 79, "what counts as mobile parity": 69, "why should editing be a stretch goal": 94,
 "deadline for the one-page plans": 48, "why run onboarding and mobile at the same time": 49, "analytics requirement before shipping": 88,
 "what are the three themes for the quarter": 84, "new admins see an empty dashboard": 50, "half of teams never invite anyone": 47,
 "what is the goal of today's session": 99, "release freeze starts when, what slips": 101, "keep pull requests small": 46,
 "password reset work and email template review": 73, "what is Maya working on": 93,
}
def noisy(s, rate, rnd):
    if re.search(r'[一-鿿]', s):
        chars = list(s); n = max(1, int(len(chars) * rate)); pool = [c for c in s if '一' <= c <= '鿿']
        for i in rnd.sample(range(len(chars)), n):
            chars[i] = rnd.choice(pool) if rnd.random() < 0.7 else ''
        return ''.join(chars)
    words = s.split(); n = max(1, int(len(words) * rate))
    for i in rnd.sample(range(len(words)), n):
        w = words[i]
        if len(w) > 3: j = rnd.randrange(1, len(w) - 1); words[i] = w[:j] + w[j + 1:] if rnd.random() < 0.5 else w[:j] + rnd.choice('aeiou') + w[j + 1:]
        elif rnd.random() < 0.5: words[i] = ''
    return ' '.join(words)
def nrm(x): return x / np.linalg.norm(x)
def embed_all(texts): return np.stack([nrm(E.embed(t)[0][:256]) for t in texts])
def lexical(q, d):  # char bigram overlap for zh, word overlap for en
    def toks(s): return set(re.findall(r'[一-鿿]{2}|[a-zA-Z]+', s.lower()) + [s[i:i+2] for i in range(len(s)-1) if '一' <= s[i] <= '鿿'])
    a, b = toks(q), toks(d); return len(a & b) / (len(a) ** 0.5 * len(b) ** 0.5 + 1e-9)
qv = embed_all(["task: search result | query: " + q for q in Q])
for label, rate in (("clean", 0), ("noise10", 0.10), ("noise20", 0.20)):
    rnd = random.Random(3); dtexts = [d if rate == 0 else noisy(d, rate, rnd) for _, d in docs]
    dv = embed_all(["title: none | text: " + d for d in dtexts]); S = qv @ dv.T
    r1 = r5 = l5 = h5 = 0
    for qi, (q, tgt) in enumerate(Q.items()):
        ti = [i for i, (di, _) in enumerate(docs) if di == tgt][0]
        order = np.argsort(-S[qi]); r1 += order[0] == ti; r5 += ti in order[:5]
        lex = np.array([lexical(q, d) for d in dtexts]); l5 += ti in np.argsort(-lex)[:5]
        hyb = S[qi] + 0.3 * lex; h5 += ti in np.argsort(-hyb)[:5]
        if rate == 0 and ti not in order[:5]: print("  miss:", q, "-> got", dtexts[order[0]][:50])
    n = len(Q); print(f"{label}: dense recall@1 {r1}/{n} recall@5 {r5}/{n} | lexical recall@5 {l5}/{n} | hybrid recall@5 {h5}/{n}")
