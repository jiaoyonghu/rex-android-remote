#!/usr/bin/env python3
"""GitHub Actions: pin sherpa-onnx 2.0.2 Java/JNI, bundle KWS and Silero.

Never uploads or modifies a user's audio, templates or model preferences.
This runs on the BUILD RUNNER before Gradle, not on the phone.
"""
import hashlib
import io
from pathlib import Path
import re
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'app/src/main/assets'
LIB = ROOT/'app/libs'
NATIVE = ROOT/'app/src/main/jniLibs/arm64-v8a'
BASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
VERS = '2.0.2'
MODEL = 'sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20'
MODEL_DIR = 'rex-sherpa-kws-zh-en-v1'


def download(url, max_bytes):
    print('Downloading', url, flush=True)
    req = urllib.request.Request(url, headers={'User-Agent':'REX-Android-build/1.0'})
    with urllib.request.urlopen(req, timeout=180) as r:
        out=io.BytesIO()
        while True:
            data=r.read(65536)
            if not data: break
            out.write(data)
            if out.tell()>max_bytes: raise RuntimeError('Download exceeds permitted size')
        return out.getvalue()


def sha256(raw, expected):
    actual=hashlib.sha256(raw).hexdigest()
    if actual!=expected: raise RuntimeError('Checksum mismatch, refuse model/runtime: '+actual)


def words_from_source():
    txt=(ROOT/'app/src/main/java/com/rex/remote/CommandPhrases.java').read_text()
    block=txt.split('public static final String[] ALL',1)[1].split('};',1)[0]
    labels=re.findall(r'"([^"\n]+)"',block)
    if len(labels)!=25: raise RuntimeError('Unexpected command list; refuse partial keywords')
    return labels


def make_keywords(tokens, lexicon):
    # Official bilingual KWS phoneme model: Chinese pinyin initials + toned
    # finals, English ARPABET phones, with a strict @spoken-keyword label.
    from pypinyin import pinyin, Style
    valid={line.split()[0] for line in tokens.decode('utf-8-sig').splitlines() if line.strip()}
    phones={}
    for line in lexicon.decode('utf-8',errors='replace').splitlines():
        parts=line.split()
        if len(parts)>1: phones.setdefault(parts[0].upper(),parts[1:])
    phones.update({'REX':['R','EH1','K','S'],
                   'ACTIVE':['AE1','K','T','IH0','V'],
                   'DISABLE':['D','IH0','S','EY1','B','AH0','L'],
                   'TURN':['T','ER1','N'],
                   'AROUND':['ER0','AW1','N','D']})
    out=[]
    for label in words_from_source():
        cleaned=re.sub(r'[，。！,!?？]', '', label)
        chunks=re.findall(r'[A-Za-z]+|[\u4e00-\u9fff]',cleaned)
        result=[]
        for chunk in chunks:
            if re.fullmatch('[A-Za-z]+',chunk):
                if chunk.upper() not in phones:
                    raise RuntimeError('Missing English pronunciation: '+chunk)
                result.extend(phones[chunk.upper()])
            else:
                initial=pinyin(chunk,style=Style.INITIALS,strict=True)[0][0]
                final=pinyin(chunk,style=Style.FINALS_TONE,strict=True)[0][0]
                if initial: result.append(initial)
                if final: result.append(final)
        absent=[p for p in result if p not in valid]
        if absent: raise RuntimeError(f'Keyword {label!r} has unsupported phones: {absent}')
        if not result: raise RuntimeError('Empty keyword: '+label)
        out.append(' '.join(result)+' @'+label.replace(' ','_'))
    print('Prepared',len(out),'distinct exact command phrases, all tokens verified', flush=True)
    return ('\n'.join(out)+'\n').encode('utf-8')


def main():
    LIB.mkdir(parents=True,exist_ok=True)
    NATIVE.mkdir(parents=True,exist_ok=True)
    ASSETS.mkdir(parents=True,exist_ok=True)
    jarname=f'sherpa-onnx-jvm-{VERS}.jar'
    raw=download(f'{BASE}/v{VERS}/{jarname}', 5_000_000)
    sha256(raw,'ebde5706d720a27407ae7277749a3ed18abcbcb16d80d3d4ef54059a62cb06d2')
    (LIB/jarname).write_bytes(raw)
    raw=download(f'{BASE}/v{VERS}/sherpa-onnx-{VERS}.aar', 90_000_000)
    sha256(raw,'6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        so_files=[name for name in archive.namelist()
                  if name.startswith('jni/arm64-v8a/') and name.endswith('.so')]
        for name in so_files:
            (NATIVE/Path(name).name).write_bytes(archive.read(name))
        if not any('libsherpa-onnx-jni.so' in name for name in so_files):
            raise RuntimeError('Official Android AAR lacks required arm64 JNI')
        print('Extracted official arm64 JNI libraries:',so_files)
    raw=download(f'{BASE}/kws-models/{MODEL}.tar.bz2', 65_000_000)
    desired=[
        'encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx',
        'decoder-epoch-13-avg-2-chunk-16-left-64.onnx',
        'joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx',
        'tokens.txt','en.phone',
    ]
    selected={}
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:bz2') as archive:
        for member in archive.getmembers():
            if not member.isfile():continue
            base=Path(member.name).name
            if base in desired and member.name.startswith(MODEL+'/'):
                if member.size>20_000_000: raise RuntimeError('Unexpected KWS model size')
                selected[base]=archive.extractfile(member).read()
    if set(selected)!=set(desired):
        raise RuntimeError('Incomplete bilingual KWS archive: '+str(set(desired)-set(selected)))
    selected['keywords.txt']=make_keywords(selected['tokens.txt'],selected['en.phone'])
    del selected['en.phone']
    silero=download(f'{BASE}/asr-models/silero_vad.onnx', 5_000_000)
    if not 400_000<len(silero)<5_000_000:raise RuntimeError('Invalid Silero VAD model length')
    selected['silero_vad.onnx']=silero
    target=ASSETS/(MODEL_DIR+'.zip')
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
        for name,body in sorted(selected.items()):
            z.writestr(MODEL_DIR+'/'+name,body)
    print('Bundled neural KWS + VAD model',target, target.stat().st_size,'bytes')


if __name__=='__main__':main()
