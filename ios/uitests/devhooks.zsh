#!/bin/zsh
# Contacts + "Email participants" device checks through the DEV hooks (no taps: used while UI Automation is off on
# the phone, see run.zsh for the XCUITest version). zsh devhooks.zsh zh|en   → ~/work/cm/<lang>/*.png + log.txt
# Demo meetings only (~/work/cm/sess, from the mail goldens); cleanup.zsh removes them and restores the people file.
until mkdir ~/work/.maclock 2>/dev/null; do sleep 15; done; trap 'rmdir ~/work/.maclock' EXIT
U=${UDID:-00008120-000148CA2EEB401E}; B=tw.com.pesi.primeminutes; L=${1:-zh}; O=~/work/cm/$L; mkdir -p $O; rm -f $O/*.png(N)
if [[ $L == zh ]]; then M=D0AB1E00-0000-4000-8000-0000000000C2; UI=zh-Hant; P=林雅婷
  FAKE='fake:林雅婷|總務部|yating.lin@example.com|yating.lin@example.com'
  OPS="link:$M:0:陳志明;email:陳志明:chihming.chen@example.com;me:陳志明;link:$M:2:王建宏"
else M=D0AB1E00-0000-4000-8000-0000000000C1; UI=en; P=Jana
  FAKE='fake:Jana Novak|Design team|jana@example.com|jana@example.com'
  OPS="link:$M:0:Ben;email:Ben:ben@example.com;me:Ben;link:$M:2:Francine;email:Francine:francine@example.com;link:$M:3:Marco"
fi
C="\"VOX_ONLY\":\"D0AB1E00\",\"VOX_UILANG\":\"$UI\",\"VOX_THEME\":\"light\",\"VOX_SHOTS\":\"1\""
for f in ~/work/cm/sess/*.json(N); do
  xcrun devicectl device copy to --device $U --domain-type appDataContainer --domain-identifier $B --source $f \
    --destination "Library/Application Support/sessions/${f:t}" >/dev/null 2>&1 || echo "copy failed: ${f:t}"
done
pull() { xcrun devicectl device copy from --device $U --domain-type appDataContainer --domain-identifier $B --source Documents/status.log --destination $O/status.log >/dev/null 2>&1; }
mark() { pull; wc -l < $O/status.log 2>/dev/null | tr -d ' ' > $O/.mark; }
since() { pull; tail -n +$(( $(cat $O/.mark 2>/dev/null || echo 0) + 1 )) $O/status.log | grep -a -E "contacts:|mail:|people:" | sed "s/^/   [$1] /" | tee -a $O/log.txt; }
shot() {  # shot <name> <snap spec> <extra env>
  rm -f ~/work/snap*.png(N); mark; zsh ~/work/snap.zsh $U $2 "$C,$3" >/dev/null
  i=1; for f in ~/work/snap*.png(N); do cp ~/work/snap$i.png $O/$1-$i.png 2>/dev/null && echo "shot $1-$i" | tee -a $O/log.txt; i=$((i+1)); done
  since $1
}
d() { echo "== ops $1" | tee -a $O/log.txt; zsh ~/work/sd-dump.zsh "$1" | grep -a -v "^#" | tee -a $O/log.txt; }
: > $O/log.txt
d "dump"
# 1. Naming a speaker → "Choose from contacts…" → the system picker (closed, nothing picked).
shot a-speaker-picker "1.5,4.5,9" "\"VOX_OPEN\":\"id:$M\",\"VOX_TAB\":\"1\",\"VOX_DIALOG\":\"speaker\",\"VOX_SPEAKER\":\"1\",\"VOX_CONTACT_TAP\":\"speaker\",\"VOX_CONTACT_CLOSE\":\"6\""
# 2. The same with a picked (demo) contact: "Name in Prime Minutes" → Save → a new person, linked, with the address.
shot b-speaker-contact "1.5,4.2,8" "\"VOX_OPEN\":\"id:$M\",\"VOX_TAB\":\"1\",\"VOX_DIALOG\":\"speaker\",\"VOX_SPEAKER\":\"1\",\"VOX_CONTACT_TAP\":\"speaker\",\"VOX_CONTACT\":\"$FAKE\",\"VOX_CONTACT_SAVE\":\"2.5\""
d "dump"
# 3. The person page (contact card + e-mails), then its "Choose from contacts…" → the picker.
shot c-person-page "3" "\"VOX_SETTINGS\":\"1\",\"VOX_PEOPLE\":\"$P\""
shot c-person-picker "3,5,9" "\"VOX_SETTINGS\":\"1\",\"VOX_PEOPLE\":\"$P\",\"VOX_CONTACT_TAP\":\"person\",\"VOX_CONTACT_CLOSE\":\"6\""
# 4. The other participants, then the meeting, the participants sheet and its preview.
d "$OPS"
shot d-summary "3" "\"VOX_OPEN\":\"id:$M\",\"VOX_TAB\":\"0\""
shot d-mail-sheet "3" "\"VOX_OPEN\":\"id:$M\",\"VOX_MAIL\":\"1\""
shot d-mail-preview "3.5" "\"VOX_OPEN\":\"id:$M\",\"VOX_MAIL\":\"preview\""
# 5. "Open in mail app": the composer if Mail has an account (closed unsent after 5 s), else mailto:, else the share sheet.
shot e-mail-open "1,4,9" "\"VOX_OPEN\":\"id:$M\",\"VOX_MAIL\":\"send\",\"VOX_MAIL_CLOSE\":\"5\""
d "dump"
