#!/bin/bash
# Prime Minutes.app for the iOS simulator without an Xcode project (swiftc + hand-made bundle).
# DEV=1 keeps the VOX_* test hooks and the Sample button (off in release builds).
#   build_app.sh [iphonesimulator x86_64]   after build_ios.sh + build_nemo_lib.sh
set -e
SDK=${1:-iphonesimulator}; ARCH=${2:-x86_64}
ROOT=$(cd "$(dirname "$0")/../.." && pwd); B=$ROOT/build-ios/$SDK-$ARCH; C=$B/crispasr; A=$ROOT/ios/App
APP=$B/PrimeMinutes.app; rm -rf $APP; mkdir -p $APP/Frameworks
T=$ARCH-apple-ios17.0$(if [ $SDK = iphonesimulator ]; then echo -simulator; fi)
MFA=; FW=$HOME/work/cl/CLiteRTLM.xcframework/ios-arm64
LRT=$HOME/work/litert-build/out-lib/libLiteRt.so   # x86_64 simulator LiteRT, see native/litert_x86_sim/
if [ $SDK = iphonesimulator ] && [ -f $LRT ]; then MFA="$B/libprimeminutes-mfa.a $(find $B -name 'libsentencepiece*.a' | head -1) $LRT"; fi
# arm64 (device or Apple-silicon simulator): CLiteRTLM.xcframework slice, stock LiteRT inside
if [ $ARCH = arm64 ]; then
  [ $SDK = iphonesimulator ] && FW=$HOME/work/cl/CLiteRTLM.xcframework/ios-arm64-simulator
  MFA="$B/libprimeminutes-mfa.a $(find $B -name 'libsentencepiece*.a' | head -1) -F$FW -framework CLiteRTLM"
fi
# The shipped speech engine is litert_host (Engine.swift), which needs the ggml-free host X-ASR (xasr_host.cc):
# ggml-free is the default. NOGGML= (set, empty) builds the old ggml/CrispASR variant for VOX_NEMO_BACKEND=ggml.
NOGGML=${NOGGML-1}
if [ -n "$NOGGML" ]; then GGML=; AUDLIB=; else GGML="$C/src/libxasr.a $C/src/libcrispasr-core.a $C/ggml/src/libggml.a $C/ggml/src/libggml-cpu.a $C/ggml/src/libggml-base.a"; AUDLIB="-L$B/audiocpp/bin -laudiocpp"; fi
xcrun --sdk $SDK swiftc -parse-as-library -O -strict-concurrency=complete $([ -n "$MFA" ] && echo -DVOX_REAL_READER) $([ -n "$DEV" ] && echo -DVOX_DEV) -target $T -import-objc-header $A/Bridging.h -Xcc -I$ROOT/ios/native -Xcc -I$ROOT/ios/native/mfa \
  $A/App.swift $A/Support.swift $A/BackgroundWork.swift $A/MeetingScreen.swift $A/SessionLibrary.swift $A/ModelDownloads.swift $A/JobRunner.swift $A/VoxTokens.swift $A/Capture.swift $A/Imports.swift $A/SettingsView.swift $A/PodcastView.swift $A/Engine.swift $A/Transcript.swift $A/Library.swift $A/UserEdits.swift $A/AudioDecode.swift $A/AudioPrep.swift $A/LongUtteranceSplitter.swift $A/BoundaryEdits.swift $A/BoundaryEditor.swift $A/OpenCC.swift $A/Queue.swift $A/SeamStitcher.swift $A/SpeakerRefs.swift $A/Podcast.swift $A/Subscriptions.swift $A/SubscriptionsView.swift $A/LibrarySources.swift $A/LibrarySourcesView.swift $A/VideoLinks.swift $A/Licenses.swift $A/HwStatus.swift $A/AgentPanel.swift $A/SessionFileCore.swift $A/SessionFile.swift $A/Export.swift $A/SessionSearchHelpers.swift $A/SessionSummaryHelpers.swift $A/SessionTranscriptHelpers.swift $A/SessionView.swift $A/Markdown.swift $A/LibraryView.swift $A/CaptureView.swift $A/Settings.swift $A/Recorder.swift $A/ModelStore.swift $A/BackgroundDownload.swift $A/AsrRegistry.swift $A/Embed.swift $A/EmbedGate.swift $A/Keywords.swift $A/AskSelect.swift $A/Ask.swift $A/MeetingSearch.swift $A/People.swift $A/PeopleStore.swift $A/PeopleViews.swift $A/PersonContact.swift $A/OSContacts.swift $A/MeetingMail.swift $A/EmailParticipants.swift $A/Voice.swift $A/VoiceStore.swift $A/VoiceGeneration.swift $A/VoiceViews.swift $A/Reader/*.swift -o $APP/PrimeMinutes \
  $B/libprimeminutes-nemo.a $MFA $GGML \
  $AUDLIB -lc++ -Xlinker -rpath -Xlinker @executable_path/Frameworks
[ -n "$NOGGML" ] || cp -L $B/audiocpp/bin/libaudiocpp.0.dylib $APP/Frameworks/libaudiocpp.0.dylib
if [ $SDK = iphonesimulator ] && [ -f $LRT ]; then cp $LRT $APP/Frameworks/; fi
if [ $ARCH = arm64 ]; then cp -R $FW/CLiteRTLM.framework $APP/Frameworks/; fi
cp $A/Info.plist $A/Resources/PrivacyInfo.xcprivacy $A/Resources/AppIcon60x60@2x.png $A/Resources/AppIcon60x60@3x.png $A/Resources/sample.wav $A/Resources/pre_encode_512x1024.f32 $APP/
cp -R $A/Resources/opencc $A/Resources/model_cards $A/Resources/speaker_cards $A/Resources/en.lproj $A/Resources/zh-Hant.lproj $A/Resources/zh-Hans.lproj $A/Resources/mail $APP/
# App icon from the asset catalog (App Store: Assets.car + CFBundleIconName; also writes AppIcon60x60@2x/@3x.png)
xcrun actool $A/Resources/Assets.xcassets --compile $APP --platform $SDK --minimum-deployment-target 17.0 --target-device iphone \
  --app-icon AppIcon --output-partial-info-plist $B/assetcatalog-info.plist --output-format human-readable-text >/dev/null
/usr/libexec/PlistBuddy -c "Add :UIDeviceFamily array" -c "Add :UIDeviceFamily:0 integer 1" $APP/Info.plist
codesign -f -s - $APP/Frameworks/*.framework $APP 2>/dev/null || codesign -f -s - $APP   # the simulator refuses unsigned bundles
echo "built $APP"
