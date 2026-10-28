"""本地假模型服务：给模拟器里的 App 当 OpenAI 兼容接口用。

目的不是「假装模型能答题」，而是把 App 这条链路（拼 prompt → 发图 → 解析 → 回填）
真的走一遍，看它到底填不填、填得对不对。真实模型的答案质量另说。

用法：python tools/mock_llm.py 8080
模拟器里 App 的接口地址填 http://10.0.2.2:8080/v1
"""
import json
import os
import re
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = os.path.join(tempfile.gettempdir(), "mock_llm.log")

# 每题拖多久再回。测「答到一半换作业」时要给用户/脚本留出切换的时间窗口；
# 平时留 0，跑一遍全流程不用等。
DELAY = float(os.environ.get("MOCK_DELAY", "0") or 0)

# 官方答案（用于验证回填顺序是否正确）
SECTION = {
    7: ["C", "A", "B", "D", "A", "C", "B"],           # 1-7
    8: ["C", "A", "B", "D", "B", "A", "D", "A"],     # 8-15
    10: ["D", "A", "C", "B", "C", "D", "A", "D", "C", "B"],  # 16-25
}


def log(line):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line, flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            return self._json(200, {"object": "list", "data": [{"id": "mock-vision", "object": "model"}]})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            req = json.loads(raw)
        except Exception:
            return self._json(400, {"error": {"message": "bad json"}})

        msgs = req.get("messages") or []
        user = next((m for m in msgs if m.get("role") == "user"), {})
        content = user.get("content")
        texts, images = [], 0
        if isinstance(content, str):
            texts.append(content)
        elif isinstance(content, list):
            for part in content:
                if part.get("type") == "text":
                    texts.append(part.get("text") or "")
                elif part.get("type") == "image_url":
                    images += 1
        prompt = "\n".join(texts)

        log("=" * 60)
        log(f"model={req.get('model')} images={images} prompt_chars={len(prompt)} t={time.strftime('%H:%M:%S')}")
        log(prompt[:900])
        if DELAY > 0:
            time.sleep(DELAY)

        if images:
            m = re.search(r"共\s*(\d+)\s*项", prompt)
            count = int(m.group(1)) if m else 0
            ans = SECTION.get(count)
            if ans is None:
                ans = ["A"] * max(count, 1)
            body = json.dumps({"answers": ans}, ensure_ascii=False)
            log(f"-> image block: count={count} answers={ans}")
        else:
            body = json.dumps({"answer": "C", "conf": 0.95})
            log("-> text answer C/0.95")

        self._json(200, {
            "id": "mock",
            "object": "chat.completion",
            "model": "mock-vision",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": body}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    open(LOG, "w").close()
    print(f"mock llm on :{port}", flush=True)
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
