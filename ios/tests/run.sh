#!/bin/bash
# Reader parity on a macOS host (Swift toolchain only): ios/tests/run.sh
set -e
D=$(cd "$(dirname "$0")" && pwd); O=${TMPDIR:-/tmp}/vox-reader-tests; mkdir -p $O
R=$D/../App/Reader; swiftc -parse-as-library -O $R/ModelCard.swift $R/MeetingReader.swift $R/ReaderLlm.swift $R/ReaderProtocol.swift $D/ReaderParityTests.swift -o $O/reader-tests
VOX_MODEL_CARDS=$D/../App/Resources/model_cards $O/reader-tests $D/golden
swiftc -parse-as-library -O $D/../App/Keywords.swift $D/KeywordsTests.swift -o $O/keywords-tests && $O/keywords-tests
swiftc -parse-as-library -O $R/ModelCard.swift $R/MeetingReader.swift $R/ReaderLlm.swift $R/ReaderProtocol.swift $R/DownloadPlan.swift $D/GuardAndPlanTests.swift -o $O/guard-tests
VOX_MODEL_CARDS=$D/../App/Resources/model_cards $O/guard-tests
swiftc -parse-as-library -O $R/MfaCompute.swift $R/ResumePlan.swift $D/EngineAndResumeTests.swift -o $O/engine-tests && $O/engine-tests
swiftc -parse-as-library -O $D/../App/AskSelect.swift $R/ReaderProtocol.swift $D/AskSelectTests.swift -o $O/ask-select-tests && $O/ask-select-tests
swiftc -parse-as-library -O $D/../App/People.swift $D/PeopleTests.swift -o $O/people-tests && $O/people-tests
swiftc -parse-as-library -O $D/../App/AsrRegistry.swift $D/AsrRegistryTests.swift -o $O/asr-registry-tests && $O/asr-registry-tests
swiftc -parse-as-library -O $D/../App/Transcript.swift $D/../App/BoundaryEdits.swift $D/../App/LongUtteranceSplitter.swift $D/BoundaryEditsTests.swift -o $O/boundary-tests && $O/boundary-tests
swiftc -parse-as-library -O $D/../App/People.swift $D/../App/Voice.swift $D/VoiceTests.swift -o $O/voice-tests
VOX_SPEAKER_CARDS=$D/../App/Resources/speaker_cards $O/voice-tests
swiftc -parse-as-library -O $D/../App/People.swift $D/../App/Voice.swift $D/../App/VoiceGeneration.swift $D/VoiceGenerationTests.swift -o $O/voice-gen-tests && $O/voice-gen-tests
swiftc -parse-as-library -O $D/../App/People.swift $D/../App/MeetingMail.swift $D/MeetingMailTests.swift -o $O/mail-tests
VOX_MAIL_DIR=$D/../App/Resources/mail $O/mail-tests $D/golden_mail $D/../App/Resources/model_cards/gemma4-meeting-agent-zh.json
swiftc -parse-as-library -O $D/../App/MeetingSearch.swift $D/MeetingSearchTests.swift -o $O/meeting-search-tests && $O/meeting-search-tests
swiftc -parse-as-library -O $D/../App/EmbedGate.swift $D/EmbedGateTests.swift -o $O/embed-gate-tests && $O/embed-gate-tests
swiftc -parse-as-library -O $R/ModelCard.swift $R/MeetingReader.swift $R/ReaderLlm.swift $R/ReaderProtocol.swift $R/ReaderSummarizer.swift $D/../App/Transcript.swift $D/../App/BoundaryEdits.swift $D/../App/LongUtteranceSplitter.swift $D/ProseTests.swift -o $O/prose-tests
VOX_MODEL_CARDS=$D/../App/Resources/model_cards $O/prose-tests $D/golden
swiftc -parse-as-library -O $R/ReaderGate.swift $D/ReaderGateTests.swift -o $O/reader-gate-tests && $O/reader-gate-tests
swiftc -parse-as-library -O $D/../App/People.swift $D/../App/Transcript.swift $D/../App/BoundaryEdits.swift $D/../App/LongUtteranceSplitter.swift $D/../App/SeamStitcher.swift $D/../App/SpeakerRefs.swift $D/SeamAndRefsTests.swift -o $O/seam-refs-tests && $O/seam-refs-tests
swiftc -parse-as-library -O $D/../App/AudioDecode.swift $D/PrimingTests.swift -o $O/priming-tests && $O/priming-tests
swiftc -parse-as-library -O $D/../App/People.swift $D/../App/Transcript.swift $D/../App/BoundaryEdits.swift $D/../App/LongUtteranceSplitter.swift $D/../App/SpeakerRefs.swift $D/LiveRenameTests.swift -o $O/live-rename-tests && $O/live-rename-tests
swiftc -parse-as-library -O $D/../App/OpenCC.swift $D/../App/Transcript.swift $D/../App/BoundaryEdits.swift $D/../App/LongUtteranceSplitter.swift $R/ModelCard.swift $R/MeetingReader.swift $R/ReaderLlm.swift $R/ReaderProtocol.swift $D/OpenCCTests.swift -o $O/opencc-tests
VOX_OPENCC_DIR=$D/../App/Resources/opencc $O/opencc-tests $D/golden
swiftc -parse-as-library -O $D/../App/BackgroundDownload.swift $D/BackgroundDownloadTests.swift -o $O/bg-download-tests && $O/bg-download-tests
