#!/bin/bash
# Note10+ test app only (studio.primeminutes.androidtest). For each variant (base = shipped 480 ms; s1920 = punct 1920 ms
# via the DEBUG asr_override dir), import each file, log the engine line + app VmHWM, pull the saved meeting.
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_xasr; P=studio.primeminutes.androidtest; R=/data/local/tmp/luigi_xasr
OV=files/models/nemo/asr_override
$A shell svc power stayon true </dev/null
$A install -r $S/app-debug.apk </dev/null | tail -1
$A shell run-as $P touch files/dev_hold_reader </dev/null
$A shell run-as $P rm -rf files/library/20261018-094502 </dev/null
$A shell mkdir -p $R </dev/null
$A push $S/xasr_stream.tflite $S/xasr_host.bin $R/ </dev/null >/dev/null; $A shell chmod 644 $R/xasr_stream.tflite $R/xasr_host.bin </dev/null
for v in ${VARIANTS:-base s1920}; do
  if [ $v = s1920 ]; then $A shell run-as $P mkdir -p $OV </dev/null; $A shell run-as $P cp $R/xasr_stream.tflite $R/xasr_host.bin $OV/ </dev/null
  else $A shell run-as $P rm -rf $OV </dev/null; fi
  echo "== variant $v override: $($A shell run-as $P ls $OV </dev/null 2>&1 | tr -d '\r' | tr '\n' ' ')"
  for f in ${FILES:-weiya L_R003S02C02 ES2004b}; do
    $A shell am force-stop $P </dev/null
    sleep 90; echo "-- $v $f $(date +%T) $($A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r')"
    $A push $S/$f.m4a /sdcard/Download/x-$v-$f.m4a </dev/null >/dev/null
    $A shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/x-$v-$f.m4a </dev/null >/dev/null
    sleep 8
    id=$($A shell content query --uri content://media/external/audio/media --projection _id:_display_name </dev/null | grep -a "x-$v-$f.m4a" | sed 's/.*_id=\([0-9]*\).*/\1/' | head -1)
    before=$($A shell run-as $P ls files/library </dev/null | tr -d '\r' | sort)
    $A logcat -c </dev/null; t0=$(date +%s)
    $A shell am start -W -a android.intent.action.VIEW -d content://media/external/audio/media/$id -t audio/mp4 --grant-read-uri-permission -n $P/studio.primeminutes.MainActivity </dev/null >/dev/null
    hwm=0
    for i in $(seq 1 240); do sleep 10
      pid=$($A shell pidof $P </dev/null | tr -d '\r'); [ -n "$pid" ] && { h=$($A shell "grep -a VmHWM /proc/$pid/status" </dev/null | awk '{print $2}'); [ "${h:-0}" -gt "$hwm" ] && hwm=$h; }
      l=$($A logcat -d </dev/null | grep -a "primeminutes-nemo: audio" | tail -1); [ -n "$l" ] && break; done
    echo "$l"; echo "import_wall $(( $(date +%s) - t0 )) s  VmHWM ${hwm} kB"
    $A logcat -d </dev/null | grep -a -i "xasr\|asr_override\|litert: \|finish failed" | tail -5
    new=$(comm -13 <(echo "$before") <($A shell run-as $P ls files/library </dev/null | tr -d '\r' | sort) | head -1)
    sleep 15
    $A exec-out run-as $P cat files/library/$new/pending_progress.json > $S/out_${v}_$f.json </dev/null
    echo "meeting $new json $(stat -c %s $S/out_${v}_$f.json)"
    case "$new" in 2026[0-9]*-[0-9]*) $A shell run-as $P rm -rf files/library/$new </dev/null ;; esac
    $A shell rm -f /sdcard/Download/x-$v-$f.m4a </dev/null
  done
done
$A shell run-as $P rm -rf $OV </dev/null
$A shell run-as $P rm -f files/dev_hold_reader </dev/null
$A shell rm -rf $R </dev/null
$A shell svc power stayon false </dev/null
echo N10XASRDONE
