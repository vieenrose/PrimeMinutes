import sys; sys.path.insert(0, "/home/luigi/src/nemo-x-asr-diarizer.cpp/tools/litert-export")
import xasr_stream_torch as X
X.CHUNK = 96; X.IN_FRAMES = 96 * 2 + 13
m = X.build("/home/luigi/xasr-reexport/icefall", sys.argv[1]); X.export_fp32(m, sys.argv[2]); X.validate_tflite(m, sys.argv[2], 4)
