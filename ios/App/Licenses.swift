import SwiftUI

/// The third-party components inside the iOS app, with their licenses: Settings › About › Open-source licenses.
/// Kept in step with NOTICE at the repository root (its iOS section): a dependency added or removed in
/// ios/native, the model cards or the speaker cards changes both.
enum OpenSourceLicenses {
    struct Component: Hashable { let name: String; let license: String; let url: String }
    struct Group: Hashable { let title: String; let items: [Component] }
    static let groups: [Group] = [
        Group(title: "Speech, speakers and AI notes (native)", items: [
            Component(name: "nemo-x-asr-diarizer", license: "Apache-2.0", url: "https://github.com/vieenrose/nemo-x-asr-diarizer.cpp"),
            Component(name: "LiteRT", license: "Apache-2.0", url: "https://github.com/google-ai-edge/LiteRT"),
            Component(name: "LiteRT-LM (mobile fused attention fork)", license: "Apache-2.0", url: "https://github.com/vieenrose/LiteRT-LM"),
            Component(name: "SentencePiece", license: "Apache-2.0", url: "https://github.com/google/sentencepiece"),
        ]),
        Group(title: "Models and data (downloaded or bundled)", items: [
            Component(name: "X-ASR zh-en speech model", license: "Apache-2.0", url: "https://huggingface.co/Luigi/primeminutes-nemo-litert"),
            Component(name: "Nemotron-3 Diarization model", license: "OpenMDW-1.1", url: "https://huggingface.co/Luigi/primeminutes-nemo-litert"),
            Component(name: "Gemma-4 E2B / E4B meeting models", license: "Apache-2.0", url: "https://huggingface.co/Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT"),
            Component(name: "EmbeddingGemma 2 270M", license: "Gemma Terms of Use", url: "https://huggingface.co/Luigi/primeminutes-embed-litert"),
            Component(name: "CAM++ zh-en (3D-Speaker)", license: "Apache-2.0", url: "https://huggingface.co/Luigi/primeminutes-speaker-litert"),
            Component(name: "OpenCC dictionaries", license: "Apache-2.0", url: "https://github.com/BYVoid/OpenCC"),
        ]),
    ]
}

struct LicensesView: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        List {
            ForEach(OpenSourceLicenses.groups, id: \.self) { g in
                Section(g.title) {
                    ForEach(g.items, id: \.self) { c in
                        Button { if let u = URL(string: c.url) { openURL(u) } } label: {
                            HStack { Text(c.name).foregroundStyle(.primary); Spacer(); Text(c.license).font(.footnote).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            Section { Text("Prime Minutes · GPL-3.0-or-later").font(.footnote).foregroundStyle(.secondary) }
        }
        .navigationTitle(L("about_licenses"))
    }
}
