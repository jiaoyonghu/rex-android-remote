#!/usr/bin/env python3
"""Download official openWakeWord v0.5.1 frozen ONNX speech feature frontend.
For training personal *classifiers*, not the pretrained English wake classifiers.
No user recordings are sent anywhere.
"""
from __future__ import annotations
import os
from pathlib import Path
import tempfile
import urllib.request

BASE = 'https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/'
MODELS = {
    'melspectrogram.onnx': (20_000, 5_000_000),
    'embedding_model.onnx': (200_000, 8_000_000),
}
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/openwakeword'


def fetch(name: str, lower: int, upper: int) -> bytes:
    req = urllib.request.Request(BASE + name, headers={'User-Agent': 'REx-Android-build/1.2'})
    with urllib.request.urlopen(req, timeout=90) as response:
        length = response.headers.get('content-length')
        if length and int(length) > upper:
            raise RuntimeError(f'Unexpected large model: {name}')
        data = response.read(upper + 1)
    if not lower <= len(data) <= upper or data[:50].lstrip().lower().startswith((b'<!doctype', b'<html')):
        raise RuntimeError(f'Invalid ONNX download {name}: {len(data)} bytes')
    return data


def main() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, (lower, upper) in MODELS.items():
        path = ROOT / name
        if path.is_file() and lower <= path.stat().st_size <= upper:
            print('Using existing openWakeWord frontend:', name)
            continue
        print('Downloading official openWakeWord frontend:', name, flush=True)
        data = fetch(name, lower, upper)
        with tempfile.NamedTemporaryFile(mode='wb', dir=ROOT, suffix='.tmp', delete=False) as out:
            out.write(data)
            tmp = Path(out.name)
        os.replace(tmp, path)
        print('Prepared:', name, len(data), 'bytes', flush=True)
    print('openWakeWord: prepared pretrained neural speech feature extractors (no trained Chinese head)')

if __name__ == '__main__':
    main()
