"""Offline X-ASR encoder as torch module: (x [1,T,80], x_lens) -> encoder_out [1,T',512] (incl. joiner.encoder_proj)."""
import sys, torch, torch.nn as nn
sys.path.insert(0, "/home/luigi/src/nemo-x-asr-diarizer.cpp/tools/litert-export")
import xasr_stream_torch as X
ICE = "/home/luigi/xasr-reexport/icefall"
import os
def load(ckpt=os.environ.get("XCKPT", "/home/luigi/voxsum-models/xoff-import/xoff_native.pt")):
    m = X.build(ICE, ckpt); m.encoder.chunk_size = (-1,); m.encoder.left_context_frames = (-1,); return m.eval()
class OffEnc(nn.Module):
    def __init__(s, m): super().__init__(); s.e, s.emb, s.p = m.encoder, m.encoder_embed, m.joiner.encoder_proj
    def forward(s, x, x_lens):
        e = s.emb; x = e.convnext(e.conv(x.unsqueeze(1))); b, c, t, f = x.size()
        x = e.out_norm(e.out(x.transpose(1, 2).reshape(b, t, c * f))); xl = (x_lens - 7) // 2
        from icefall.utils import make_pad_mask
        mask = make_pad_mask(xl, x.shape[1])
        y, yl = s.e(x.permute(1, 0, 2), xl, src_key_padding_mask=mask)
        return s.p(y.permute(1, 0, 2)), yl
