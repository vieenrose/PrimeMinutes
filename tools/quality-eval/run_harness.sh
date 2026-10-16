#!/usr/bin/env bash
# Run QualityEvalHarness (app/src/test/.../eval) in one mode with a JSON config:
#   tools/quality-eval/run_harness.sh asr|reader|ask <config.json>
# The host JNI libraries come from build_host_jni.sh (java.library.path follows LD_LIBRARY_PATH on Linux).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export PM_EVAL_MODE=$1 PM_EVAL_CFG=$(readlink -f "$2")
# The JVM applies the user locale to native printf; the JNI encodings no longer use %f (nemo/fixed_fmt.h),
# C.UTF-8 just keeps any other native text stable.
export LC_ALL=C.UTF-8
export LD_LIBRARY_PATH=$ROOT/tools/quality-eval/build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
cd "$ROOT"
./gradlew --no-daemon -q -PpmEval :app:testDebugUnitTest --tests studio.primeminutes.eval.QualityEvalHarness \
  -Dorg.gradle.jvmargs=-Xmx3g --rerun
