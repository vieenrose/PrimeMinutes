import Foundation

// ios/tests/run.sh: VideoLinks (YouTube / PeerTube links are recognised, never imported).
@main struct VideoLinksTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }
    static func main() {
        check(VideoLinks.isVideoLink("https://www.youtube.com/watch?v=jNQXAC9IVRw"), "youtube watch")
        check(VideoLinks.isVideoLink("see https://youtu.be/jNQXAC9IVRw"), "youtu.be in text")
        check(VideoLinks.isVideoLink("https://framatube.org/w/9c9de5e8-0a1e-484a-b099-e80766180a6d"), "peertube /w/")
        check(VideoLinks.isVideoLink("https://peertube.example/videos/watch/9c9de5e8-0a1e-484a"), "peertube /videos/watch/")
        check(!VideoLinks.isVideoLink(nil) && !VideoLinks.isVideoLink(""), "empty")
        check(!VideoLinks.isVideoLink("https://feeds.example.com/podcast.xml"), "feed")
        check(!VideoLinks.isVideoLink("https://example.com/w/x"), "short id")
        check(VideoLinks.isPeerTubeFile("https://framatube.org/static/web-videos/abc-240.mp4"), "peertube file")
        check(!VideoLinks.isPeerTubeFile("https://cdn.example.com/podcast/ep1.mp3"), "podcast file")
        print("VideoLinks: OK")
    }
}
