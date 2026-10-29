#!/bin/zsh
# AirDrop checks: receiver test on one phone, sender test on the other (AirDropUITests). Holds the Mac mutex.
#   zsh airdrop.zsh <tag> <sender udid> <receiver udid> [KEY=VALUE ...]   (PM_MODE=p1, PM_TARGET=..., PM_OPEN=1, PM_WAIT=120)
R=${0:A:h}; T=$1-$(date +%H%M%S); S=$2; V=$3; shift 3; O=/Volumes/DevSSD/airdrop/ui; mkdir -p $O
until mkdir ~/work/.maclock 2>/dev/null; do sleep 15; done; trap 'rmdir ~/work/.maclock' EXIT
source ~/motdepass.sh; security unlock-keychain -p "$MOTDEPASSE" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1
envs=(); for kv in "$@"; do envs+=("TEST_RUNNER_$kv"); done
cd $R
for d in $S $V; do env $envs xcodebuild build-for-testing -allowProvisioningUpdates -project PrimeMinutesUITests.xcodeproj -scheme PrimeMinutesUITests -destination id=$d -derivedDataPath $O/dd-$d > $O/bft-$d.log 2>&1 || { grep -a error: $O/bft-$d.log | head; exit 1; }; done
env $envs xcodebuild test-without-building -project PrimeMinutesUITests.xcodeproj -scheme PrimeMinutesUITests -destination id=$V -derivedDataPath $O/dd-$V \
  -only-testing:PrimeMinutesUITests/AirDropUITests/testReceive -resultBundlePath $O/$T-recv.xcresult > $O/$T-recv.log 2>&1 &
sleep 25
env $envs xcodebuild test-without-building -project PrimeMinutesUITests.xcodeproj -scheme PrimeMinutesUITests -destination id=$S -derivedDataPath $O/dd-$S \
  -only-testing:PrimeMinutesUITests/AirDropUITests/testSend -resultBundlePath $O/$T-send.xcresult > $O/$T-send.log 2>&1
wait
for k in send recv; do grep -a -E "Test Case .*(passed|failed)|error:|XCTAssert" $O/$T-$k.log | head -5
  rm -rf $O/att-$T-$k; mkdir -p $O/att-$T-$k; xcrun xcresulttool export attachments --path $O/$T-$k.xcresult --output-path $O/att-$T-$k >/dev/null 2>&1; echo "$k: $(ls $O/att-$T-$k | wc -l) attachments"; done
