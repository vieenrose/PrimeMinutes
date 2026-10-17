#!/bin/zsh
# Removes the demo meetings and restores the people directory saved in ~/work/cm/bk/people (see run.zsh).
until mkdir ~/work/.maclock 2>/dev/null; do sleep 15; done; trap 'rmdir ~/work/.maclock' EXIT
U=${UDID:-00008120-000148CA2EEB401E}; B=tw.com.pesi.primeminutes
xcrun devicectl device process launch --device $U --terminate-existing -e '{"VOX_DEV":"1","VOX_DEL":"D0AB1E00-0000-4000-8000-0000000000C1,D0AB1E00-0000-4000-8000-0000000000C2"}' $B >/dev/null; sleep 8
xcrun devicectl device copy to --device $U --domain-type appDataContainer --domain-identifier $B --source ~/work/cm/bk/people/people.json \
  --destination "Library/Application Support/people/people.json" >/dev/null 2>&1 && echo "people.json restored"
sleep 2; zsh ~/work/sd-dump.zsh "list" | head -12
xcrun devicectl device info files --device $U --domain-type appDataContainer --domain-identifier $B --subdirectory "Library/Application Support" 2>&1 | grep -a -E "D0AB1E00|people/" 
xcrun devicectl device uninstall app --device $U tw.com.pesi.primeminutes.uitests.xctrunner 2>&1 | tail -1
