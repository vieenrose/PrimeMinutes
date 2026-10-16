// macOS host test: Ask's keyword terms and BM25 (same cases as Android AskRetrievalTest).
import Foundation

@main struct KeywordsTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        check(Keywords.terms("頭獎是 iPhone 16".lowercased()), ["頭獎", "獎是", "iphone", "16"], "terms")
        check(Keywords.terms("車"), ["車"], "lone character")
        let s = Keywords.bm25("禮券多少", ["會議討論預算", "會議討論禮券五百元", "會議討論場地"])
        check(s[1] > 0, true, "rare term scores"); check(s[0], 0, "no shared term"); check(s[2], 0, "no shared term")
        print(failures == 0 ? "keywords PASS" : "keywords: \(failures) failure(s)"); exit(failures == 0 ? 0 : 1)
    }
}
