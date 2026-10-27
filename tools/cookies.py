"""把模拟器 WebView 里的 cookie 导出成 curl 用的 header 串。

用法：python tools/cookies.py > cookies.txt
"""
import json
import urllib.request

import websocket


def main():
    with urllib.request.urlopen("http://127.0.0.1:9222/json/list", timeout=10) as r:
        pages = [t for t in json.load(r) if t.get("type") == "page"]
    ws = websocket.create_connection(
        pages[0]["webSocketDebuggerUrl"], timeout=20, suppress_origin=True
    )
    try:
        ws.send(json.dumps({"id": 1, "method": "Network.getCookies", "params": {}}))
        while True:
            msg = json.loads(ws.recv())
            if msg.get("id") == 1:
                cookies = msg.get("result", {}).get("cookies", [])
                print("; ".join(f"{c['name']}={c['value']}" for c in cookies))
                return
    finally:
        ws.close()


if __name__ == "__main__":
    main()
