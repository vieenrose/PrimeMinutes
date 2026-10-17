#!/bin/zsh
# Contacts + "Email participants" device checks with real taps (XCUITest driving the installed DEV app).
# On the Mac: zsh ~/VoxSumDroid/ios/uitests/run.zsh <TestName|all> [KEY=VALUE ...]
#   KEY=VALUE become the runner's environment (PM_UILANG=zh-Hant, PM_PICK=1, PM_OPEN=1, PM_PERSON=..., PM_ADDR=...).
# SEED=1 puts the demo meetings (~/work/cm/sess/*.json, from the mail goldens) in the library first; cleanup.zsh removes them
# and restores the people directory backed up in ~/work/cm/bk. Holds the Mac mutex.
until mkdir ~/work/.maclock 2>/dev/null; do sleep 15; done; trap 'rmdir ~/work/.maclock' EXIT
U=${UDID:-00008120-000148CA2EEB401E}; B=tw.com.pesi.primeminutes; T=$1; shift
source ~/motdepass.sh; security unlock-keychain -p "$MOTDEPASSE" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1
[[ -n $SEED ]] && for f in ~/work/cm/sess/*.json(N); do
  xcrun devicectl device copy to --device $U --domain-type appDataContainer --domain-identifier $B --source $f \
    --destination "Library/Application Support/sessions/${f:t}" >/dev/null 2>&1 || echo "copy failed: ${f:t}"
done
R=~/work/cm/res-$T-$(date +%H%M%S).xcresult
envs=(); for kv in "$@"; do envs+=("TEST_RUNNER_$kv"); done
only=(); [[ $T != all ]] && only=(-only-testing:PrimeMinutesUITests/ContactsMailUITests/$T)
cd ~/VoxSumDroid/ios/uitests && env $envs xcodebuild test -allowProvisioningUpdates -project PrimeMinutesUITests.xcodeproj \
  -scheme PrimeMinutesUITests -destination id=$U $only -resultBundlePath $R > ~/work/cm/xcb-$T.log 2>&1
echo "xcodebuild exit $?"
grep -a -E "error:|Test Case .*(passed|failed)|XCTAssert|failed -|\*\* TEST" ~/work/cm/xcb-$T.log | head -30
O=~/work/cm/att-$T; rm -rf $O; mkdir -p $O
xcrun xcresulttool export attachments --path $R --output-path $O >/dev/null 2>&1 && echo "attachments: $O ($(ls $O | wc -l))"
xcrun devicectl device copy from --device $U --domain-type appDataContainer --domain-identifier $B --source Documents/status.log --destination ~/work/cm/status.log >/dev/null 2>&1
grep -a -E "mail:|contacts:|people:" ~/work/cm/status.log | tail -8
