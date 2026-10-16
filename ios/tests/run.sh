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
swiftc -parse-as-library -O $D/../App/AskSelect.swift $D/AskSelectTests.swift -o $O/ask-select-tests && $O/ask-select-tests
