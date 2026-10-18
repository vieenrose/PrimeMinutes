import sys; sys.path.insert(0, "/home/luigi/src/nemo-x-asr-diarizer.cpp/tools/litert-export")
import xasr_stream_torch as X
X.CHUNK = 96; X.IN_FRAMES = 96 * 2 + 13
m = X.build("/home/luigi/xasr-reexport/icefall", sys.argv[1])
X.validate_vs_onnx(m, sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 6)
