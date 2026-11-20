#!/usr/bin/env python3
"""Prepare openWakeWord's official TFLite feature extractors.

Sherpa-onnx owns native libonnxruntime.so. Installing a second ORT Java JNI
version in the same APK may cause OrtGetApiBase unresolved-symbol failures.
The official TFLite variants avoid that ABI conflict.
"""
from __future__ import annotations
import os
from pathlib import Path
import tempfile
import urllib.request

BASE = 'https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/'
MODELS = {
    'melspectrogram.tflite': (10_000, 5_000_000),
    'embedding_model.tflite': (100_000, 8_000_000),
}
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/openwakeword'


def fetch(name, lower, upper):
    req = urllib.request.Request(BASE + name, headers={'User-Agent': 'REx-Android-build/1.2.2'})
    with urllib.request.urlopen(req, timeout=90) as response:
        length = response.headers.get('content-length')
        if length and int(length) > upper:
            raise RuntimeError(f'Unexpected large model: {name}')
        data = response.read(upper + 1)
    if not lower <= len(data) <= upper or data[:50].lstrip().lower().startswith((b'<!doctype', b'<html')):
        raise RuntimeError(f'Invalid TFLite download {name}: {len(data)} bytes')
    # TFLite FlatBuffer file identifier at byte offset 4
    if data[4:8] != b'TFL3':
        raise RuntimeError(f'Not a TFLite FlatBuffer: {name}')
    return data


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, (lower, upper) in MODELS.items():
        path = ROOT / name
        # Avoid silently retaining stale or corrupted assets: validate header.
        if path.is_file() and lower <= path.stat().st_size <= upper:
            with path.open('rb') as f:
                header = f.read(8)
            if header[4:8] == b'TFL3':
                print('Using existing TFLite frontend:', name, flush=True)
                continue
        print('Downloading openWakeWord TFLite frontend:', name, flush=True)
        data = fetch(name, lower, upper)
        with tempfile.NamedTemporaryFile(mode='wb', dir=ROOT, suffix='.tmp', delete=False) as out:
            out.write(data)
            temp = Path(out.name)
        os.replace(temp, path)
        print('Prepared:', name, len(data), 'bytes', flush=True)
    # Remove older incompatible ONNX assets if this is a reused Actions workspace.
    for obsolete in ('melspectrogram.onnx', 'embedding_model.onnx'):
        (ROOT / obsolete).unlink(missing_ok=True)
    print('openWakeWord frozen TFLite features ready (sherpa keeps its own ONNX Runtime)')


if __name__ == '__main__':
    main()
