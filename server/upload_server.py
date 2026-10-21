#!/usr/bin/env python3
"""接收拍照上传，把 JPEG 保存到指定目录。

用法:
  python3 server/upload_server.py --dir /你的目录
  python3 server/upload_server.py --dir ./uploads --host 0.0.0.0 --port 8080

平板把 JPEG 正文 POST 到 /upload?dir=子目录
dir 是相对 --dir 的子目录。留空则直接保存在 --dir。
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

MAX_BYTES = 20 * 1024 * 1024
SAVE_DIR = Path.cwd()


def safe_dir(root: Path, rel: str) -> Path:
    text = unquote(rel).replace("\\", "/").strip()
    if text.startswith("/"):
        raise ValueError("目录必须是相对路径")
    parts = [part for part in text.split("/") if part not in ("", ".")]
    if any(part == ".." for part in parts):
        raise ValueError("目录不能包含 ..")
    dest = root.joinpath(*parts).resolve() if parts else root.resolve()
    root_resolved = root.resolve()
    if dest != root_resolved and root_resolved not in dest.parents:
        raise ValueError("目录超出保存范围")
    return dest


class UploadHandler(BaseHTTPRequestHandler):
    server_version = "PhotoUpload/1.0"

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self._json(200, {"ok": True, "save_dir": str(SAVE_DIR.resolve())})
            return
        self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/upload":
            self._json(404, {"ok": False, "error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._json(400, {"ok": False, "error": "bad content length"})
            return
        if length <= 0 or length > MAX_BYTES:
            self._json(413, {"ok": False, "error": "照片大小不合法"})
            return
        data = self.rfile.read(length)
        if len(data) != length:
            self._json(400, {"ok": False, "error": "照片数据不完整"})
            return
        if not data.startswith(b"\xff\xd8\xff"):
            self._json(400, {"ok": False, "error": "只接收 JPEG"})
            return
        rel = parse_qs(parsed.query).get("dir", [""])[0]
        try:
            folder = safe_dir(SAVE_DIR, rel)
        except ValueError as exc:
            self._json(400, {"ok": False, "error": str(exc)})
            return
        folder.mkdir(parents=True, exist_ok=True)
        name = datetime.now().strftime("%Y%m%d_%H%M%S_%f")[:-3] + ".jpg"
        dest = folder / name
        dest.write_bytes(data)
        print(f"saved {dest} ({len(data)} bytes)", flush=True)
        self._json(200, {"ok": True, "saved": str(dest), "name": name, "bytes": len(data)})

    def log_message(self, fmt: str, *args) -> None:
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    global SAVE_DIR
    default_dir = Path(__file__).resolve().parent.parent / "uploads"
    parser = argparse.ArgumentParser(description="把平板拍到的照片保存到指定目录")
    parser.add_argument("--dir", default=str(default_dir), help="照片保存目录")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    SAVE_DIR = Path(args.dir).expanduser().resolve()
    SAVE_DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), UploadHandler)
    server.allow_reuse_address = True  # 加在 server_bind 之前
    print(f"upload server http://{args.host}:{args.port}/upload", flush=True)
    print(f"save dir {SAVE_DIR}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopped", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
