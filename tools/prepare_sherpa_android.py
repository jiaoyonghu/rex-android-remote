#!/usr/bin/env python3
"""GitHub Actions: download pinned sherpa-onnx JNI, Silero VAD and CAM++ only.

REx v1.5 removes Zipformer KWS model downloads and keywords generation.
This BUILD-TIME script never reads or uploads user microphone recordings.
"""
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
LIB=ROOT/'app/libs'
NATIVE=ROOT/'app/src/main/jniLibs/arm64-v8a'
BASE='https://github.com/k2-fsa/sherpa-onnx/releases/download'
VERS='2.0.2'
MODEL_DIR='rex-sherpa-vad-campplus-v15'


def download(url,limit):
    print('Downloading',url,flush=True)
    request=urllib.request.Request(url,headers={'User-Agent':'REx-Android-build/1.5'})
    with urllib.request.urlopen(request,timeout=180) as reply:
        chunks=io.BytesIO()
        while True:
            data=reply.read(65536)
            if not data:break
            chunks.write(data)
            if chunks.tell()>limit:raise RuntimeError('Download exceeds limit')
        return chunks.getvalue()


def sha256(raw,expected):
    actual=hashlib.sha256(raw).hexdigest()
    if actual!=expected:raise RuntimeError('Unexpected model/JNI checksum '+actual)


def main():
    for directory in (LIB,NATIVE,ASSETS):directory.mkdir(parents=True,exist_ok=True)
    jar=f'sherpa-onnx-jvm-{VERS}.jar'
    data=download(f'{BASE}/v{VERS}/{jar}',5_000_000)
    sha256(data,'ebde5706d720a27407ae7277749a3ed18abcbcb16d80d3d4ef54059a62cb06d2')
    (LIB/jar).write_bytes(data)
    data=download(f'{BASE}/v{VERS}/sherpa-onnx-{VERS}.aar',90_000_000)
    sha256(data,'6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        libs=[name for name in z.namelist()
              if name.startswith('jni/arm64-v8a/') and name.endswith('.so')]
        if not any('libsherpa-onnx-jni.so' in name for name in libs):
            raise RuntimeError('Official sherpa AAR has no arm64 JNI')
        for name in libs:(NATIVE/Path(name).name).write_bytes(z.read(name))
        print('Official arm64 native libraries:',libs,flush=True)

    vad=download(f'{BASE}/asr-models/silero_vad.onnx',5_000_000)
    if not 400_000<len(vad)<5_000_000:raise RuntimeError('Silero model size invalid')
    speaker_name='3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx'
    campplus=download(
        f'https://huggingface.co/bitsydarel/campplus-onnx/resolve/main/{speaker_name}',
        35_000_000)
    if len(campplus)!=28281164:raise RuntimeError('CAM++ unexpected model size')
    sha256(campplus,'aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2')
    asset=ASSETS/(MODEL_DIR+'.zip')
    with zipfile.ZipFile(asset,'w',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
        z.writestr(MODEL_DIR+'/silero_vad.onnx',vad)
        z.writestr(MODEL_DIR+'/campplus_zh_en.onnx',campplus)
    print('VAD/CAM++ offline assets prepared WITHOUT Zipformer KWS:',asset,
          asset.stat().st_size,flush=True)

if __name__=='__main__':main()
