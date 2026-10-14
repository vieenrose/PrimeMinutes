#!/usr/bin/env python3
"""Publishes the EmbeddingGemma 2 270M LiteRT repack (tools/embed-export/repack.py) to the Hub.

Usage: python3 tools/embed-export/hf_upload.py /path/to/embeddinggemma [repo_id]

Uploads embedder.tflite, encoder.tflite and tokenizer.spiece (= sections/Section2_SP_Tokenizer.bin)
to a public model repo and prints the commit sha to pin in EmbedRegistry.revision (Android) and
ReaderModel.embed (iOS). Needs `pip install huggingface_hub` and a token (`hf auth login`).
"""
import sys, os
from huggingface_hub import HfApi, CommitOperationAdd

src = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else "Luigi/primeminutes-embed-litert"
files = {
    "embedder.tflite": os.path.join(src, "embedder.tflite"),
    "encoder.tflite": os.path.join(src, "encoder.tflite"),
    "tokenizer.spiece": os.path.join(src, "sections", "Section2_SP_Tokenizer.bin"),
}
for p in files.values():
    assert os.path.isfile(p), p
readme = """---
license: gemma
base_model: google/embeddinggemma-2-270m
tags: [litert, tflite, embeddings, prime-minutes]
---
# EmbeddingGemma 2 270M — LiteRT repack for Prime Minutes

`embedder.tflite` (embedding table + pooling/projection) and `encoder.tflite` (transformer, dynamic int8
weights, fp32 activations) split out of Google's `embeddinggemma-2-text-270m.litertlm`, plus the
SentencePiece model. Used by Prime Minutes (Android/iOS) for on-device meeting search and Ask.
Subject to the Gemma Terms of Use.
"""
api = HfApi()
api.create_repo(repo, repo_type="model", exist_ok=True)
ops = [CommitOperationAdd(path_in_repo=k, path_or_fileobj=v) for k, v in files.items()]
ops.append(CommitOperationAdd(path_in_repo="README.md", path_or_fileobj=readme.encode()))
info = api.create_commit(repo_id=repo, operations=ops, commit_message="EmbeddingGemma 2 270M LiteRT repack (dynamic int8)")
print("commit:", info.oid)
print(f"pin: https://huggingface.co/{repo}/resolve/{info.oid}")
