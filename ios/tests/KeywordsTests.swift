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
        let d = Keywords.doc("禮券禮券 iphone 16")
        check(d.length, 5, "doc length"); check(d.tf(Keywords.code("禮券")), 2, "tf"); check(d.tf(Keywords.code("16")), 1, "tf word"); check(d.tf(Keywords.code("手機")), 0, "tf absent")
        check(Keywords.code("16"), Int32(truncatingIfNeeded: 1573), "Java hashCode parity")   // "16".hashCode() on Android
        var table = (0..<65536).map { UInt16($0) }; table[Int("禮".utf16.first!)] = "礼".utf16.first!
        check(Keywords.fold("禮券 iPhone", table), "礼券 iphone", "fold")
        print(failures == 0 ? "keywords PASS" : "keywords: \(failures) failure(s)"); exit(failures == 0 ? 0 : 1)
    }
}
