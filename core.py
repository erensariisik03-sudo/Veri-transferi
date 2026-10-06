"""Kivy'den bağımsız çekirdek: Flask sunucusu, QR üretimi, yardımcı fonksiyonlar."""
import os
import hmac
import socket
import secrets
import mimetypes
import threading
import urllib.parse

from flask import Flask, Response
from werkzeug.serving import make_server

CHUNK = 128 * 1024

# ----------------------------------------------------------------------
#  Yardımcılar
# ----------------------------------------------------------------------


def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


def get_free_port(default_port=8080):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("0.0.0.0", default_port))
        port = default_port
    except OSError:
        sock.bind(("0.0.0.0", 0))
        port = sock.getsockname()[1]
    finally:
        sock.close()
    return port


def human_size(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def unique_path(directory, filename):
    path = os.path.join(directory, filename)
    base, ext = os.path.splitext(filename)
    n = 1
    while os.path.exists(path):
        path = os.path.join(directory, f"{base} ({n}){ext}")
        n += 1
    return path


def filename_from_disposition(cd):
    """Content-Disposition başlığından dosya adını çıkarır (filename* öncelikli)."""
    if not cd:
        return None
    name = None
    if "filename*=" in cd:
        raw = cd.split("filename*=", 1)[1].split(";")[0].strip()
        if "''" in raw:
            raw = raw.split("''", 1)[1]
        name = urllib.parse.unquote(raw.strip("\"'"))
    elif "filename=" in cd:
        name = cd.split("filename=", 1)[1].split(";")[0].strip().strip("\"'")
    return os.path.basename(name) if name else None


def make_qr_buffer(data, border=2, scale=6):
    """QR kodu RGB piksel tamponu olarak döndürür -> (genişlik, bytes). Üstten alta satırlar."""
    import segno
    qr = segno.make_qr(data, error="m")
    matrix = [list(row) for row in qr.matrix]
    n = len(matrix)
    size = (n + 2 * border) * scale
    white = b"\xff\xff\xff"
    black = b"\x00\x00\x00"
    rows = []
    blank = white * size
    for _ in range(border * scale):
        rows.append(blank)
    for r in matrix:
        line = b"".join((black if v else white) * scale for v in r)
        line = white * (border * scale) + line + white * (border * scale)
        for _ in range(scale):
            rows.append(line)
    for _ in range(border * scale):
        rows.append(blank)
    return size, b"".join(rows)


# ----------------------------------------------------------------------
#  Paylaşım durumu
# ----------------------------------------------------------------------
STATE = {
    "file": "",
    "token": "",
    "total": 0,
    "sent": 0,
    "status": "idle",  # idle | waiting | sending | done | aborted
    "req": 0,
}


def reset_share(path):
    STATE.update(file=path, token=secrets.token_urlsafe(12), total=os.path.getsize(path),
                 sent=0, status="waiting", req=0)
    return STATE["token"]


def clear_share():
    STATE.update(file="", token="", total=0, sent=0, status="idle", req=0)


# ----------------------------------------------------------------------
#  Flask
# ----------------------------------------------------------------------
flask_app = Flask(__name__)


@flask_app.route("/")
def index():
    return "LAN Dosya Transferi çalışıyor. Dosyayı almak için gönderici ekrandaki QR kodu okutun.", 200


@flask_app.route("/d/<token>")
def download(token):
    path = STATE["file"]
    if not path or not STATE["token"] or not hmac.compare_digest(token, STATE["token"]):
        return "Yetkisiz giriş", 403
    if not os.path.isfile(path):
        return "Dosya bulunamadı", 404

    name = os.path.basename(path)
    total = os.path.getsize(path)
    STATE["req"] += 1
    my_id = STATE["req"]
    STATE.update(sent=0, total=total, status="sending")

    def stream():
        try:
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    yield chunk
                    if STATE["req"] == my_id:
                        STATE["sent"] += len(chunk)
            if STATE["req"] == my_id:
                STATE["status"] = "done"
        except GeneratorExit:
            if STATE["req"] == my_id:
                STATE["status"] = "aborted"
            raise

    ascii_name = name.encode("ascii", "replace").decode().replace('"', "_").replace("?", "_")
    headers = {
        "Content-Length": str(total),
        "Content-Disposition":
            f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{urllib.parse.quote(name)}",
    }
    mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
    return Response(stream(), mimetype=mime, headers=headers)


class ServerThread(threading.Thread):
    def __init__(self, port):
        super().__init__(daemon=True)
        self.server = make_server("0.0.0.0", port, flask_app, threaded=True)

    def run(self):
        self.server.serve_forever()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
