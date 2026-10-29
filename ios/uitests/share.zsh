#!/bin/zsh
# Share v2 device checks (ShareUITests) on the installed DEV app. Holds the Mac mutex.
#   zsh share.zsh <TestName> <out dir> [KEY=VALUE ...]     (UDID=…; PM_UILANG=en, PM_ONLINE=1, PM_WHAT=session…)
# SEED=<dir>: copies <dir>/*.json into the library and <dir>/*.m4a into the audio folder first (demo meetings only);
# DEL=<ids>: removes those meetings afterwards (VOX_DEL), with their audio.
R=${0:A:h}; T=$1; O=$2; shift 2; mkdir -p $O
until mkdir ~/work/.maclock 2>/dev/null; do sleep 15; done; trap 'rmdir ~/work/.maclock' EXIT
U=${UDID:-00008150-00015C223A9B401C}; B=tw.com.pesi.primeminutes
source ~/motdepass.sh; security unlock-keychain -p "$MOTDEPASSE" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1
put() { xcrun devicectl device copy to --device $U --domain-type appDataContainer --domain-identifier $B --source $1 --destination "$2" >/dev/null 2>&1 || echo "copy failed: $1"; }
if [[ -n $SEED ]]; then
  for f in $SEED/*.json(N); do put $f "Library/Application Support/sessions/${f:t}"; done
  for f in $SEED/*.m4a(N); do put $f "Library/Application Support/audio/${f:t}"; done
fi
envs=(); for kv in "$@"; do envs+=("TEST_RUNNER_$kv"); done
X=$O/res-$T-$(date +%H%M%S).xcresult
cd $R && env $envs xcodebuild test -allowProvisioningUpdates -project PrimeMinutesUITests.xcodeproj -scheme PrimeMinutesUITests -destination id=$U \
  -derivedDataPath $O/dd -only-testing:PrimeMinutesUITests/ShareUITests/$T -resultBundlePath $X > $O/xcb-$T.log 2>&1
echo "xcodebuild exit $?"
grep -a -E "error:|Test Case .*(passed|failed)|XCTAssert|failed -|\*\* TEST" $O/xcb-$T.log | head -20
A=$O/att-$T; rm -rf $A; mkdir -p $A
xcrun xcresulttool export attachments --path $X --output-path $A >/dev/null 2>&1 && echo "attachments: $A ($(ls $A | wc -l))"
xcrun devicectl device copy from --device $U --domain-type appDataContainer --domain-identifier $B --source Documents/status.log --destination $O/status.log >/dev/null 2>&1
grep -a "share:" $O/status.log | tail -8
if [[ -n $DEL ]]; then
  xcrun devicectl device process launch --device $U --terminate-existing -e "{\"VOX_DEV\":\"1\",\"VOX_DEL\":\"$DEL\"}" $B >/dev/null; sleep 8
  xcrun devicectl device info files --device $U --domain-type appDataContainer --domain-identifier $B --subdirectory "Library/Application Support" 2>&1 | grep -a -c "D0AB1E00" | sed 's/^/demo files left: /'
fi
