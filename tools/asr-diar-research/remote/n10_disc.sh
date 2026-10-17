#!/bin/bash
# discovery: push sherpa binaries, print help + cpu topology. Standalone, /data/local/tmp only.
A="adb -s RF8M834KRGA"
$A shell mkdir -p /data/local/tmp/luigi_asrprobe </dev/null
$A push /tmp/luigi_asrprobe/bin0/bin/sherpa-onnx /tmp/luigi_asrprobe/bin0/bin/sherpa-onnx-offline /data/local/tmp/luigi_asrprobe/ </dev/null
$A shell 'cd /data/local/tmp/luigi_asrprobe && chmod 755 sherpa-onnx* && ./sherpa-onnx-offline --help 2>&1 | grep -a -i -E "fire|qwen|sense|paraformer|funasr|num-threads|provider" ' </dev/null
$A shell 'for c in 0 1 2 3 4 5 6 7; do echo cpu$c $(cat /sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq); done; getprop ro.product.model; getprop ro.board.platform; cat /sys/class/thermal/thermal_zone0/temp; dumpsys battery | grep -a -E "level|temperature"' </dev/null
