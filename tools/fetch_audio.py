"""把听力作业的 8 段音频抓到本地：/ananas/status 换地址，再带 cookie 下载。"""
import json
import os
import tempfile
import urllib.request

BASE = os.environ.get("ASR_WORK", os.path.join(tempfile.gettempdir(), "asr3"))
COOKIE = open(os.path.join(BASE, "..", "cookies.txt"), encoding="utf-8").read().strip()

IDS = [
    "c34460e6ecb28acdc4f69b0e61b252f8",
    "e2b6f2dccfb0a7da79ddbacff220681f",
    "7c5a64d888ef3d8fdbb57e1c3030e820",
    "9193548e698a34d6c25e87098dcc79a9",
    "d584de3da94e2321c94239768f1250e5",
    "63ac074a8d4f33591306ee373eb5c027",
    "15c98ee4f0b50f657fcec0e0d0084bd9",
    "727cb8a3d79d16248c9ca8d0131c28db",
]

HEADERS = {
    "Cookie": COOKIE,
    "Referer": "https://mooc1.chaoxing.com/",
    "User-Agent": "Mozilla/5.0",
}


def get(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


for oid in IDS:
    meta = json.loads(get(f"https://mooc1.chaoxing.com/ananas/status/{oid}"))
    name = meta.get("filename") or oid
    url = meta.get("download") or meta.get("http")
    data = get(url)
    path = os.path.join(BASE, name)
    with open(path, "wb") as f:
        f.write(data)
    print(f"{name}\t{len(data)/1048576:.2f} MB\t{meta.get('duration')}s")
