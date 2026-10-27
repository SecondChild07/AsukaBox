"""用 SenseVoice（和 App 里同一个模型、同一套参数）把 8 段听力转成文字。

模型从桌面的装机包里解出来，保证和手机上跑的是同一份文件。
"""
import glob
import os
import sys
import tempfile
import zipfile

import miniaudio
import numpy as np
import sherpa_onnx

BASE = os.environ.get("ASR_WORK", os.path.join(tempfile.gettempdir(), "asr3"))
ZIP = os.environ.get("ASR_MODEL_ZIP", "sensevoice-model.zip")
MODEL_DIR = os.path.join(BASE, "model")

if not os.path.isdir(MODEL_DIR):
    os.makedirs(MODEL_DIR, exist_ok=True)
    with zipfile.ZipFile(ZIP) as z:
        for name in z.namelist():
            base = name.split("/")[-1]
            if base in ("model.int8.onnx", "tokens.txt"):
                with open(os.path.join(MODEL_DIR, base), "wb") as f:
                    f.write(z.read(name))
print("模型:", os.listdir(MODEL_DIR), file=sys.stderr)

recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=os.path.join(MODEL_DIR, "model.int8.onnx"),
    tokens=os.path.join(MODEL_DIR, "tokens.txt"),
    num_threads=6,
    use_itn=True,
    language="auto",
    debug=False,
)


def decode_16k(path):
    d = miniaudio.decode_file(
        path, output_format=miniaudio.SampleFormat.FLOAT32, nchannels=1, sample_rate=16000
    )
    return np.frombuffer(d.samples, dtype=np.float32)


total = ""
for mp3 in sorted(glob.glob(os.path.join(BASE, "*.mp3"))):
    samples = decode_16k(mp3)
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, samples)
    recognizer.decode_stream(stream)
    text = stream.result.text.strip()
    print(f"\n===== {os.path.basename(mp3)} ({len(samples)/16000:.0f}s) =====")
    print(text)
    total += f"\n===== {os.path.basename(mp3)} =====\n{text}\n"

with open(os.path.join(BASE, "transcript.txt"), "w", encoding="utf-8") as f:
    f.write(total)
print("\n已写入 transcript.txt", file=sys.stderr)
