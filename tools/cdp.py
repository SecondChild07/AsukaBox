"""对模拟器里的 WebView 跑一段 JS（CDP over adb forward 9222）。

用法：python cdp.py "document.title"
      python cdp.py -f some.js
"""
import json
import sys
import urllib.request

import websocket


def targets():
    with urllib.request.urlopen("http://127.0.0.1:9222/json/list", timeout=10) as r:
        return json.load(r)


def evaluate(expr: str, timeout: int = 30) -> str:
    pages = [t for t in targets() if t.get("type") == "page"]
    if not pages:
        return "NO_PAGE"
    ws = websocket.create_connection(
        pages[0]["webSocketDebuggerUrl"], timeout=timeout, suppress_origin=True
    )
    try:
        ws.send(json.dumps({
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {"expression": expr, "returnByValue": True, "awaitPromise": True},
        }))
        while True:
            msg = json.loads(ws.recv())
            if msg.get("id") == 1:
                res = msg.get("result", {})
                if "exceptionDetails" in res:
                    return "EXCEPTION: " + json.dumps(res["exceptionDetails"], ensure_ascii=False)[:800]
                return json.dumps(res.get("result", {}).get("value"), ensure_ascii=False)
    finally:
        ws.close()


if __name__ == "__main__":
    if sys.argv[1] == "-f":
        code = open(sys.argv[2], encoding="utf-8").read()
    else:
        code = sys.argv[1]
    print(evaluate(code))
