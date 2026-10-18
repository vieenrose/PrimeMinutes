import sys; sys.path.insert(0, "/home/luigi/voxsum-models/xoff-import")
import torch, xoff_torch as XT, litert_torch, argparse
ap = argparse.ArgumentParser(); ap.add_argument("--T", type=int, nargs="+", default=[2000]); ap.add_argument("out"); a = ap.parse_args()
enc = XT.OffEnc(XT.load()).eval()
conv = None
for T in a.T:
    args = (torch.zeros(1, T, 80), torch.tensor([T], dtype=torch.int32))
    conv = (litert_torch.signature if conv is None else conv.signature)(f"enc{T}", enc, args)
conv.convert().export(a.out); print("ok", a.out)
