#!/usr/bin/env bash
# Host run of the e-mail prompt variants (EmailEvalHarness) over the journals of tools/email-eval/make_cfg.py.
#   tools/email-eval/run.sh <out dir>     (outputs: <meeting>.<variant>.<model>.json, resumable)
# Needs tools/quality-eval/build/libprimeminutes-mfa.so (tools/quality-eval/build_host_jni.sh).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-/home/luigi/voxsum-models/eval-corpora/work/email_eval}
python3 "$ROOT/tools/email-eval/make_cfg.py" "$OUT"
export PM_EMAIL_CFG=$OUT/cfg.json LC_ALL=C.UTF-8
export LD_LIBRARY_PATH=$ROOT/tools/quality-eval/build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
cd "$ROOT"
nice -n 5 ./gradlew --no-daemon -q -PpmEval :app:testDebugUnitTest --tests studio.primeminutes.eval.EmailEvalHarness \
  -Dorg.gradle.jvmargs=-Xmx3g --rerun
