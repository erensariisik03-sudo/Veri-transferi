"""Hızlı Aktar - çekirdek: protokol, sunucu, indirici ve ortak havuz.

Android uygulamasıyla aynı protokolü konuşur:
  GET /ping?k=TOKEN        -> "ok"
  GET /manifest?k=TOKEN    -> {"files":[{"i":0,"n":"ad","s":123}, ...]}
  GET /file/<i>?k=TOKEN    -> dosya (Range: bytes=N- destekli)
"""
import hashlib
import http.client
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlencode, urlparse

UDP_PORT = 47474
KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CHUNK = 256 * 1024

HTTP_ERRORS = (OSError, ValueError, http.client.HTTPException)

# Sistem proxy'sini atla: hedefler hep yerel ağdaki cihazlar.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_get(url, timeout=15, headers=None):
    return _OPENER.open(urllib.request.Request(url, headers=headers or {}), timeout=timeout)


# ------------------------------------------------------------------ yardımcılar

def fmt_bytes(n):
    n = float(n)
    if n >= 1 << 30:
        return "%.2f GB" % (n / (1 << 30))
    if n >= 1 << 20:
        return "%.1f MB" % (n / (1 << 20))
    if n >= 1 << 10:
        return "%.0f KB" % (n / (1 << 10))
    return "%d B" % n


def fmt_speed(bps):
    return fmt_bytes(bps) + "/sn"


def safe_name(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name or "").strip(" .")
    return name or "dosya"


def app_dir():
    base = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    d = os.path.join(base, "HizliAktar")
    os.makedirs(d, exist_ok=True)
    return d


def default_dest():
    return os.path.join(os.path.expanduser("~"), "Downloads", "HizliAktar")


def local_ips():
    """Yerel (özel) IPv4 adresleri."""
    found = []

    def add(ip):
        try:
            a = ipaddress.ip_address(ip)
        except ValueError:
            return
        if a.is_private and not a.is_loopback and not a.is_link_local and ip not in found:
            found.append(ip)

    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            add(info[4][0])
    except OSError:
        pass
    return found


def broadcast_addrs():
    """Her yerel adres için /24 yayın adresi + genel yayın."""
    out = []
    for ip in local_ips():
        b = ip.rsplit(".", 1)[0] + ".255"
        if b not in out:
            out.append(b)
    out.append("255.255.255.255")
    return out


def unique_path(folder, name):
    os.makedirs(folder, exist_ok=True)
    name = safe_name(name)
    stem, ext = os.path.splitext(name)
    target = os.path.join(folder, name)
    n = 1
    while os.path.exists(target):
        target = os.path.join(folder, "%s (%d)%s" % (stem, n, ext))
        n += 1
    return target


def save_download(part, name, dest_dir):
    if not os.path.exists(part):
        open(part, "wb").close()
    target = unique_path(dest_dir, name)
    shutil.move(part, target)
    return target


# ---------------------------------------------------------- yapılandırma

class Config:
    def __init__(self):
        self.path = os.path.join(app_dir(), "config.json")
        self.data = {}
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {}
        changed = False
        if "device_id" not in self.data:
            self.data["device_id"] = secrets.token_hex(4)
            changed = True
        if "device_name" not in self.data:
            self.data["device_name"] = socket.gethostname() or "PC"
            changed = True
        if "dest_dir" not in self.data:
            self.data["dest_dir"] = default_dest()
            changed = True
        if changed:
            self.save()

    def get(self, k, default=None):
        return self.data.get(k, default)

    def set(self, k, v):
        self.data[k] = v
        self.save()

    def save(self):
        try:
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self.data, f)
        except OSError:
            pass


# --------------------------------------------------------- bağlantı / havuz kodu

class Link:
    """Tek seferlik QR bağlantısı: hizli://c?s=&p=&h=&t=&k="""

    def __init__(self, ssid, password, hosts, port, token):
        self.ssid, self.password, self.hosts, self.port, self.token = ssid, password, hosts, port, token

    def encode(self):
        q = []
        if self.ssid and self.password:
            q += [("s", self.ssid), ("p", self.password)]
        q += [("h", ",".join(self.hosts)), ("t", str(self.port)), ("k", self.token)]
        return "hizli://c?" + urlencode(q, quote_via=quote)

    @staticmethod
    def parse(text):
        try:
            u = urlparse((text or "").strip())
            if u.scheme != "hizli" or u.netloc != "c":
                return None
            q = parse_qs(u.query)
            hosts = [h for h in (q.get("h", [""])[0]).split(",") if h]
            port = int(q["t"][0])
            token = q["k"][0]
            if not hosts:
                return None
            return Link((q.get("s") or [None])[0], (q.get("p") or [None])[0], hosts, port, token)
        except (KeyError, ValueError, IndexError):
            return None


def new_key():
    return "".join(secrets.choice(KEY_ALPHABET) for _ in range(10))


def norm_key(text):
    return "".join(c for c in (text or "").upper() if c.isalnum())


def pretty_key(key):
    return key[:5] + "-" + key[5:]


def pool_link(key):
    return "hizli://pool?k=" + key


def parse_pool(text):
    """QR/bağlantı ya da elle yazılan koddan havuz anahtarını çıkarır."""
    text = (text or "").strip()
    try:
        u = urlparse(text)
        if u.scheme == "hizli" and u.netloc == "pool":
            key = norm_key(parse_qs(u.query).get("k", [""])[0])
            return key if len(key) == 10 else None
    except ValueError:
        return None
    key = norm_key(text)
    return key if len(key) == 10 and all(c in KEY_ALPHABET for c in key) else None


def pid_of(key):
    return hashlib.sha256(("pid:" + key).encode()).hexdigest()[:16]


def tok_of(key):
    return hashlib.sha256(("tok:" + key).encode()).hexdigest()[:24]


# ------------------------------------------------------------------ durum / hız

class SpeedMeter:
    def __init__(self):
        self.t = time.time()
        self.b = 0
        self.speed = 0.0

    def update(self, total):
        now = time.time()
        dt = now - self.t
        if dt >= 1.0:
            inst = max(0, total - self.b) / dt
            self.speed = inst if self.speed == 0 else 0.5 * self.speed + 0.5 * inst
            self.t, self.b = now, total
        return self.speed


class TransferState:
    def __init__(self):
        self.phase = "idle"  # idle|connecting|ready|transferring|reconnecting|done|error
        self.message = ""
        self.files = []  # [ad, boyut, tamamlanan]
        self.qr = None

    @property
    def total(self):
        return sum(f[1] for f in self.files)

    @property
    def done(self):
        return sum(f[2] for f in self.files)

    @property
    def percent(self):
        t = self.total
        return 0 if t <= 0 else min(100, int(self.done * 100 / t))


class SharedFile:
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        self.size = os.path.getsize(path)


# ---------------------------------------------------------------- HTTP sunucusu

class FileServer:
    """Küçük HTTP sunucusu. files() -> [SharedFile|None]; indeksler sabit kalır."""

    def __init__(self, token_ok, files, on_progress=None, on_client=None, host="0.0.0.0", port=0):
        self.token_ok, self.files = token_ok, files
        self.on_progress, self.on_client = on_progress, on_client
        self.progress_interval = 0.15
        outer = self

        class Handler(BaseHTTPRequestHandler):
            timeout = 20

            def log_message(self, *args):
                pass

            def _text(self, code, body, ctype="text/plain"):
                b = body.encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", ctype + "; charset=utf-8")
                self.send_header("Content-Length", str(len(b)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                try:
                    u = urlparse(self.path)
                    k = (parse_qs(u.query).get("k") or [None])[0]
                    if not outer.token_ok(k):
                        return self._text(403, "yetkisiz")
                    if outer.on_client:
                        outer.on_client()
                    if u.path == "/ping":
                        return self._text(200, "ok")
                    if u.path == "/manifest":
                        arr = [{"i": i, "n": f.name, "s": f.size}
                               for i, f in enumerate(outer.files()) if f is not None]
                        return self._text(200, json.dumps({"files": arr}), "application/json")
                    if u.path.startswith("/file/"):
                        return self._file(int(u.path[6:]))
                    self._text(404, "yok")
                except (ConnectionError, socket.timeout, ValueError, OSError):
                    pass

            def _file(self, idx):
                lst = outer.files()
                f = lst[idx] if 0 <= idx < len(lst) else None
                if f is None:
                    return self._text(404, "yok")
                start = 0
                m = re.match(r"bytes=(\d+)-", self.headers.get("Range", ""))
                if m:
                    start = int(m.group(1))
                if start > f.size:
                    return self._text(416, "aralik")
                self.send_response(206 if start > 0 else 200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(f.size - start))
                if start > 0:
                    self.send_header("Content-Range", "bytes %d-%d/%d" % (start, f.size - 1, f.size))
                self.send_header("Connection", "close")
                self.end_headers()
                sent = start
                last = 0.0
                with open(f.path, "rb") as fh:
                    fh.seek(start)
                    while True:
                        buf = fh.read(CHUNK)
                        if not buf:
                            break
                        self.wfile.write(buf)
                        sent += len(buf)
                        now = time.time()
                        if outer.on_progress and now - last >= outer.progress_interval:
                            last = now
                            outer.on_progress(idx, sent)
                if outer.on_progress:
                    outer.on_progress(idx, sent)

        class Server(ThreadingHTTPServer):
            daemon_threads = True
            allow_reuse_address = True

        self.httpd = Server((host, port), Handler)
        self.port = self.httpd.server_address[1]

    def start(self):
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        return self.port

    def stop(self):
        try:
            self.httpd.shutdown()
            self.httpd.server_close()
        except OSError:
            pass


# ------------------------------------------------------------ kaldığı yerden indirme

def _size(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def fetch(url_for_attempt, part, size, on_progress, cancelled, on_retry=None, max_stall=40):
    """Dosyayı `part`a indirir. Kopunca aynı byte'tan devam eder.

    url_for_attempt() her denemede çağrılır (None -> şu an yol yok).
    """
    os.makedirs(os.path.dirname(part) or ".", exist_ok=True)
    stall = 0
    while not cancelled() and _size(part) < size:
        before = _size(part)
        try:
            url = url_for_attempt()
            if url is None:
                raise OSError("yol yok")
            headers = {"Range": "bytes=%d-" % before} if before > 0 else {}
            with http_get(url, 15, headers) as r:
                code = r.status
                if code not in (200, 206):
                    raise OSError("HTTP %d" % code)
                append = code == 206 and before > 0
                got = before if append else 0
                with open(part, "ab" if append else "wb") as f:
                    while not cancelled():
                        chunk = r.read(CHUNK)
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
                        on_progress(got)
            if not cancelled() and _size(part) < size:
                raise OSError("eksik veri")
        except HTTP_ERRORS:
            if cancelled():
                return False
            stall = 1 if _size(part) > before else stall + 1
            if stall > max_stall:
                return False
            if on_retry:
                on_retry(stall)
            time.sleep(min(stall, 5))
    return not cancelled()


# ---------------------------------------------------------------- Windows Wi-Fi

_NOWIN = 0x08000000  # CREATE_NO_WINDOW


def _run(args, timeout=20):
    kw = {}
    if sys.platform == "win32":
        kw["creationflags"] = _NOWIN
    return subprocess.run(args, capture_output=True, text=True, errors="ignore", timeout=timeout, **kw)


def wifi_current_ssid():
    if sys.platform != "win32":
        return None
    try:
        out = _run(["netsh", "wlan", "show", "interfaces"]).stdout
        for line in out.splitlines():
            m = re.match(r"^\s*SSID\s*:\s*(.+?)\s*$", line)
            if m:
                return m.group(1)
    except (OSError, subprocess.SubprocessError):
        pass
    return None


_PROFILE = """<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
<name>{ssid}</name><SSIDConfig><SSID><name>{ssid}</name></SSID></SSIDConfig>
<connectionType>ESS</connectionType><connectionMode>manual</connectionMode>
<MSM><security><authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption>
<useOneX>false</useOneX></authEncryption>
<sharedKey><keyType>passphrase</keyType><protected>false</protected><keyMaterial>{key}</keyMaterial></sharedKey>
</security></MSM></WLANProfile>"""


def wifi_join(ssid, password, timeout=25):
    """Gönderenin Wi-Fi Direct/hotspot ağına katıl (yalnızca Windows)."""
    if sys.platform != "win32":
        return False
    from xml.sax.saxutils import escape
    try:
        if wifi_current_ssid() == ssid:
            return True
        fd, path = tempfile.mkstemp(suffix=".xml")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(_PROFILE.format(ssid=escape(ssid), key=escape(password)))
        try:
            _run(["netsh", "wlan", "add", "profile", "filename=" + path])
        finally:
            try:
                os.remove(path)
            except OSError:
                pass
        _run(["netsh", "wlan", "connect", "name=" + ssid, "ssid=" + ssid])
        end = time.time() + timeout
        while time.time() < end:
            if wifi_current_ssid() == ssid:
                time.sleep(2)  # IP almasını bekle
                return True
            time.sleep(1)
    except (OSError, subprocess.SubprocessError):
        pass
    return False


# ------------------------------------------------------------ 1:1 alıcı (QR bağlantısı)

class Receiver:
    def __init__(self, link, dest_dir, work_dir=None):
        self.link, self.dest = link, dest_dir
        self.work = work_dir or os.path.join(app_dir(), "incoming")
        self.state = TransferState()
        self.cancelled = False
        self.cur_host = None
        # Kendi ağı (Wi-Fi Direct/hotspot) öncelikli; yerel ağ yedek.
        self.prefer_direct = bool(link.ssid and link.password) and sys.platform == "win32"
        self._prev_wifi = None

    def cancel(self):
        self.cancelled = True

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()

    def _url(self, path, host=None):
        return "http://%s:%d%s?k=%s" % (host or self.cur_host, self.link.port, path, self.link.token)

    def _probe(self, host):
        try:
            with http_get(self._url("/ping", host), 1.5) as r:
                return r.status == 200
        except HTTP_ERRORS:
            return False

    def _find_lan(self):
        for h in self.link.hosts:
            if self.cancelled:
                return False
            if self._probe(h):
                self.cur_host = h
                return True
        return False

    def ensure_link(self):
        if self.cur_host and self._probe(self.cur_host):
            return True
        if self.prefer_direct:
            self.state.message = "Gönderenin Wi-Fi ağına bağlanılıyor…"
            if wifi_join(self.link.ssid, self.link.password) and self._find_lan():
                return True
        return self._find_lan()

    def _fail(self, msg):
        if not self.cancelled:
            self.state.phase = "error"
            self.state.message = msg

    def run(self):
        st = self.state
        st.phase, st.message = "connecting", "Gönderene bağlanılıyor…"
        if self.prefer_direct:
            self._prev_wifi = wifi_current_ssid()
        try:
            tries = 0
            while not self.cancelled and not self.ensure_link():
                tries += 1
                if tries >= 6:
                    return self._fail("Gönderene ulaşılamadı. QR/bağlantıyı yeniden al.")
                time.sleep(1.5)
            if self.cancelled:
                return
            mf = self._manifest()
            if mf is None:
                return self._fail("Dosya listesi alınamadı.")
            st.files = [[n, s, 0] for _, n, s in mf]
            for pos, (idx, name, size) in enumerate(mf):
                if not self._download(pos, idx, name, size):
                    return
            st.phase, st.message = "done", "Tamamlandı. Dosyalar: " + self.dest
        finally:
            self._restore_wifi()

    def _restore_wifi(self):
        prev = self._prev_wifi
        if prev and self.link.ssid and prev != self.link.ssid:
            try:
                _run(["netsh", "wlan", "connect", "name=" + prev])
            except (OSError, subprocess.SubprocessError):
                pass

    def _manifest(self):
        for _ in range(30):
            if self.cancelled:
                return None
            try:
                with http_get(self._url("/manifest"), 5) as r:
                    arr = json.loads(r.read().decode("utf-8"))["files"]
                return [(o["i"], o["n"], o["s"]) for o in arr]
            except HTTP_ERRORS + (KeyError,):
                time.sleep(1)
                self.ensure_link()
        return None

    def _download(self, pos, idx, name, size):
        st = self.state
        part = os.path.join(self.work, "%s_%d.part" % (self.link.token, idx))

        def progress(got):
            st.files[pos][2] = got
            st.phase, st.message = "transferring", "Aktarılıyor"

        def retry(n):
            st.phase = "reconnecting"
            st.message = "Bağlantı koptu, yeniden bağlanılıyor… (%d)" % n
            if n >= 3 and self.link.ssid and sys.platform == "win32":
                self.prefer_direct = True
            self.ensure_link()

        ok = fetch(lambda: self._url("/file/%d" % idx) if self.cur_host else None,
                   part, size, progress, lambda: self.cancelled, retry)
        if not ok:
            self._fail("Bağlantı kurtarılamadı. Yeniden bağlan; kaldığı yerden devam eder.")
            return False
        try:
            save_download(part, name, self.dest)
        except OSError as e:
            self._fail("Kaydedilemedi: %s" % e)
            return False
        st.files[pos][2] = size
        return True


# ----------------------------------------------------------- 1:1 gönderici (QR göster)

class Sender:
    """PC gönderen: dosyaları sunar, QR için bağlantı üretir (ortak yerel ağ)."""

    def __init__(self, paths):
        self.files = [SharedFile(p) for p in paths]
        self.state = TransferState()
        self.state.files = [[f.name, f.size, 0] for f in self.files]
        self.token = "".join(secrets.choice("abcdefghjkmnpqrstuvwxyz23456789") for _ in range(10))
        self.server = FileServer(lambda k: k == self.token, lambda: self.files,
                                 self._progress, self._client)
        self.reached = [0] * len(self.files)
        self.link = None

    def start(self):
        port = self.server.start()
        hosts = local_ips()
        self.link = Link(None, None, hosts, port, self.token)
        self.state.qr = self.link.encode()
        self.state.phase = "ready"
        self.state.message = ("Alıcıda 'Al' → QR'ı okut. İki cihaz aynı Wi-Fi'da olmalı."
                              if hosts else "Yerel ağ bulunamadı. Wi-Fi'ya bağlan.")

    def stop(self):
        self.server.stop()

    def _client(self):
        if self.state.phase == "ready":
            self.state.phase, self.state.message = "transferring", "Alıcı bağlandı"

    def _progress(self, idx, sent):
        if sent > self.reached[idx]:
            self.reached[idx] = sent
            self.state.files[idx][2] = sent
        self.state.phase = "transferring"
        self.state.message = "Gönderiliyor"
        if all(f[2] >= f[1] for f in self.state.files):
            self.state.phase, self.state.message = "done", "Gönderim tamamlandı"


# ------------------------------------------------------------------ ortak havuz

class Peer:
    def __init__(self, pid, name, ip, port):
        self.id, self.name, self.ip, self.port = pid, name, ip, port
        self.ver = -1
        self.fetched = -2
        self.fetching = False
        self.last = time.time()
        self.files = []  # [(index, ad, boyut)]


class PoolEngine:
    """Aynı ağdaki cihazlar birbirini bulur, paylaşılan dosyaları listeler."""

    def __init__(self, key, dest_dir, device_id, device_name, work_dir=None,
                 udp_port=UDP_PORT, targets=None, every=2.0, shared=None, on_shared=None):
        self.key, self.dest = key, dest_dir
        self.pid, self.tok = pid_of(key), tok_of(key)
        self.device_id, self.device_name = device_id, device_name
        self.work = work_dir or os.path.join(app_dir(), "incoming")
        self.udp_port, self.targets, self.every = udp_port, targets, every
        self.mine = []
        # Her açılışta farklı başlangıç: yeniden başlayan cihazın listesini diğerleri mutlaka yeniden çeker.
        self.version = int(time.time()) % (10 ** 9)
        self.on_shared = on_shared
        # Önceki oturumdan kalan paylaşımlar (boş giriş = kaldırılmış; indeksler sabit kalır).
        for p in (shared or []):
            try:
                self.mine.append(SharedFile(p) if p else None)
            except OSError:
                self.mine.append(None)
        self.peers = {}
        self.dl = {}
        self.lock = threading.RLock()
        self.running = False
        self.error = None
        self.total_bytes = 0
        self.meter = SpeedMeter()
        self.server = FileServer(lambda k: k == self.tok, lambda: list(self.mine))
        self.http_port = 0

    # -- yaşam döngüsü
    def start(self):
        self.running = True
        self.http_port = self.server.start()
        threading.Thread(target=self._announce, daemon=True).start()
        threading.Thread(target=self._listen, daemon=True).start()

    def stop(self):
        self.running = False
        self.server.stop()

    # -- paylaşım
    def add_files(self, paths):
        with self.lock:
            for p in paths:
                try:
                    self.mine.append(SharedFile(p))
                except OSError:
                    continue
            self.version += 1
        self._changed()

    def remove_file(self, idx):
        with self.lock:
            if 0 <= idx < len(self.mine):
                self.mine[idx] = None
                self.version += 1
        self._changed()

    def shared_paths(self):
        with self.lock:
            return [f.path if f is not None else "" for f in self.mine]

    def _changed(self):
        if self.on_shared:
            try:
                self.on_shared(self.shared_paths())
            except Exception:
                pass

    # -- keşif
    def _targets(self):
        return self.targets if self.targets is not None else [(b, self.udp_port) for b in broadcast_addrs()]

    def _announce(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        while self.running:
            pkt = json.dumps({"a": "hizli", "p": self.pid, "d": self.device_id,
                              "n": self.device_name, "t": self.http_port,
                              "v": self.version}).encode("utf-8")
            for host, port in self._targets():
                try:
                    s.sendto(pkt, (host, port))
                except OSError:
                    pass
            now = time.time()
            with self.lock:
                for pid in [i for i, p in self.peers.items() if now - p.last > self.every * 4.5]:
                    del self.peers[pid]
            time.sleep(self.every)
        s.close()

    def _listen(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", self.udp_port))
        except OSError as e:
            self.error = "Keşif portu açılamadı: %s" % e
            return
        s.settimeout(1.0)
        while self.running:
            try:
                data, addr = s.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            self._on_packet(data, addr)
        s.close()

    def _on_packet(self, data, addr):
        try:
            m = json.loads(data.decode("utf-8"))
            if m.get("a") != "hizli" or m.get("p") != self.pid or m.get("d") == self.device_id:
                return
            did, port, ver = str(m["d"]), int(m["t"]), int(m["v"])
        except (ValueError, KeyError, TypeError):
            return
        with self.lock:
            p = self.peers.get(did)
            if p is None:
                p = self.peers[did] = Peer(did, m.get("n", "?"), addr[0], port)
            p.name, p.ip, p.port, p.ver, p.last = m.get("n", "?"), addr[0], port, ver, time.time()
            if p.ver != p.fetched and not p.fetching:
                p.fetching = True
                threading.Thread(target=self._fetch_manifest, args=(p, ver), daemon=True).start()

    def _fetch_manifest(self, p, ver):
        try:
            url = "http://%s:%d/manifest?k=%s" % (p.ip, p.port, self.tok)
            with http_get(url, 4) as r:
                arr = json.loads(r.read().decode("utf-8"))["files"]
            with self.lock:
                p.files = [(o["i"], o["n"], o["s"]) for o in arr]
                p.fetched = ver
        except HTTP_ERRORS + (KeyError,):
            pass
        finally:
            p.fetching = False

    # -- indirme
    def download(self, owner, idx):
        with self.lock:
            p = self.peers.get(owner)
            entry = next((f for f in (p.files if p else []) if f[0] == idx), None)
            if entry is None or self.dl.get((owner, idx), {}).get("status") == "downloading":
                return
            self.dl[(owner, idx)] = {"done": 0, "size": entry[2], "status": "downloading", "path": None}
        threading.Thread(target=self._download, args=(owner, idx, entry), daemon=True).start()

    def _download(self, owner, idx, entry):
        _, name, size = entry
        key = (owner, idx)
        st = self.dl[key]
        part = os.path.join(self.work, "pool_%s_%s_%d_%d.part" % (self.pid, owner, idx, size))
        last = [_size(part)]

        def progress(got):
            self.total_bytes += max(0, got - last[0])
            last[0] = got
            st["done"] = got

        def url():
            p = self.peers.get(owner)
            return None if p is None else "http://%s:%d/file/%d?k=%s" % (p.ip, p.port, idx, self.tok)

        if fetch(url, part, size, progress, lambda: not self.running):
            try:
                st["path"] = save_download(part, name, self.dest)
                st["done"], st["status"] = size, "done"
                return
            except OSError:
                pass
        st["status"] = "error"

    # -- görünüm
    def snapshot(self):
        speed = self.meter.update(self.total_bytes)
        with self.lock:
            mine = [(i, f.name, f.size) for i, f in enumerate(self.mine) if f is not None]
            devices = [{"id": p.id, "name": p.name, "ip": p.ip, "files": list(p.files)}
                       for p in sorted(self.peers.values(), key=lambda x: x.name.lower())]
            dl = {k: dict(v) for k, v in self.dl.items()}
        return {"mine": mine, "devices": devices, "dl": dl, "speed": speed, "error": self.error}
