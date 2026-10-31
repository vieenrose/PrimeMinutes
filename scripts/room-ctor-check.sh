#!/usr/bin/env bash
# Fails if a Room database implementation in the APK lost its no-arg constructor (R8): Room creates
# it by reflection, so the app would crash at launch (issue #9). Usage: room-ctor-check.sh <dexdump> <apk>
set -euo pipefail
"$1" "$2" 2>/dev/null | awk '
  /Class descriptor/ { cls=$4; gsub(/\x27/,"",cls); impl=(cls ~ /_Impl;$/); next }
  impl && /Superclass/ { sup=$3; gsub(/\x27/,"",sup); if (sup !~ /Database;$/) impl=0; else { seen[cls]=1 } next }
  impl && /name *: \x27<init>\x27/ { init=1; next }
  impl && init && /type *: \x27\(\)V\x27/ { ok[cls]=1; init=0; next }
  /type *:/ { init=0 }
  END { n=0; bad=0; for (c in seen) { n++; if (!(c in ok)) { print "missing no-arg constructor: " c; bad=1 } }
        if (n==0) { print "no Room database implementations found"; exit 1 }
        if (bad) exit 1; print n " Room database implementation(s) keep their constructor" }'
