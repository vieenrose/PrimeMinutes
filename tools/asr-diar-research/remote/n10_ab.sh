#!/bin/bash
# Note10+ test app only: for each APK tag, install, import the 尾牙 demo, wait for the engine line + saved meeting, pull it.
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_t05stage; P=studio.primeminutes.androidtest
$A shell svc power stayon true </dev/null
for tag in "$@"; do
  sleep 90; echo "== $tag $(date +%T) $($A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r')"
  $A install -r $S/app-debug-$tag.apk </dev/null | tail -1
  $A push $S/weiya-meeting-zhTW.m4a /sdcard/Download/weiya-$tag.m4a </dev/null >/dev/null
  $A shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/weiya-$tag.m4a </dev/null >/dev/null
  sleep 8
  id=$($A shell content query --uri content://media/external/audio/media --projection _id:_display_name </dev/null | grep -a "weiya-$tag.m4a" | sed 's/.*_id=\([0-9]*\).*/\1/' | head -1)
  before=$($A shell run-as $P ls files/library </dev/null | tr -d '\r' | sort)
  $A logcat -c </dev/null
  $A shell am start -W -a android.intent.action.VIEW -d content://media/external/audio/media/$id -t audio/mp4 --grant-read-uri-permission -n $P/studio.primeminutes.MainActivity </dev/null >/dev/null
  for i in $(seq 1 60); do sleep 20; l=$($A logcat -d </dev/null | grep -a "primeminutes-nemo: audio" | tail -1); [ -n "$l" ] && { echo "$l"; break; }; done
  new=$(comm -13 <(echo "$before") <($A shell run-as $P ls files/library </dev/null | tr -d '\r' | sort) | head -1)
  for i in $(seq 1 30); do sleep 20; sz=$($A shell run-as $P stat -c %s files/library/$new/session.m4a </dev/null | tr -d '\r'); [ "${sz:-0}" -gt 100000 ] && break; done
  sleep 10
  $A exec-out run-as $P cat files/library/$new/session.m4a > $S/ab_$tag.m4a </dev/null
  echo "meeting $new size $(stat -c %s $S/ab_$tag.m4a)"
  $A shell rm -f /sdcard/Download/weiya-$tag.m4a </dev/null
done
$A shell svc power stayon false </dev/null
echo N10ABDONE
