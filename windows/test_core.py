"""Yerel (loopback) testler: python test_core.py"""
import hashlib
import os
import shutil
import tempfile
import time

import core


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def make_file(folder, name, size):
    p = os.path.join(folder, name)
    with open(p, "wb") as f:
        f.write(os.urandom(size))
    return p


def wait(cond, timeout=10, what=""):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.1)
    raise AssertionError("zaman aşımı: " + what)


def test_links():
    l = core.Link("DIRECT-HA-ab12", "p@ss word", ["192.168.49.1", "10.0.0.5"], 5555, "tok123")
    s = l.encode()
    assert s.startswith("hizli://c?s=DIRECT-HA-ab12&p="), s
    p = core.Link.parse(s)
    assert (p.ssid, p.password, p.hosts, p.port, p.token) == (l.ssid, l.password, l.hosts, 5555, "tok123")
    # Kotlin tarafının ürettiği biçim (ssid/parola yok, virgül %2C)
    k = core.Link.parse("hizli://c?h=192.168.1.5%2C10.0.0.2&t=8080&k=abc")
    assert k.ssid is None and k.hosts == ["192.168.1.5", "10.0.0.2"] and k.port == 8080
    assert core.Link.parse("hizli://pool?k=ABCDE12345") is None
    assert core.Link.parse("http://x") is None
    key = core.new_key()
    assert len(key) == 10 and core.parse_pool(core.pool_link(key)) == key
    assert core.parse_pool(core.pretty_key(key).lower()) == key
    assert core.parse_pool("abc") is None
    # Kotlin ile aynı türetme: sha256("pid:"+key)[:16]
    assert core.pid_of("ABCDEFGHJK") == hashlib.sha256(b"pid:ABCDEFGHJK").hexdigest()[:16]
    print("ok links")


def test_server_resume(tmp):
    src = make_file(tmp, "big.bin", 3 * 1024 * 1024 + 123)
    srv = core.FileServer(lambda k: k == "T", lambda: [core.SharedFile(src), None])
    port = srv.start()
    base = "http://127.0.0.1:%d" % port
    # yetkisiz
    try:
        core.http_get(base + "/ping?k=bad")
        raise AssertionError("403 bekleniyordu")
    except OSError as e:
        assert "403" in str(e)
    assert core.http_get(base + "/ping?k=T").read() == b"ok"
    import json
    mf = json.loads(core.http_get(base + "/manifest?k=T").read())
    assert mf["files"] == [{"i": 0, "n": "big.bin", "s": os.path.getsize(src)}], mf
    # kaldığı yerden
    part = os.path.join(tmp, "p", "x.part")
    os.makedirs(os.path.dirname(part))
    with open(src, "rb") as f, open(part, "wb") as o:
        o.write(f.read(1_000_000))
    prog = []
    ok = core.fetch(lambda: base + "/file/0?k=T", part, os.path.getsize(src), prog.append, lambda: False)
    assert ok and sha(part) == sha(src) and prog[0] > 1_000_000
    # 404 (None girdisi)
    try:
        core.http_get(base + "/file/1?k=T")
        raise AssertionError("404 bekleniyordu")
    except OSError as e:
        assert "404" in str(e)
    srv.stop()
    print("ok server+resume")


def test_midstream_drop(tmp):
    src = make_file(tmp, "drop.bin", 2 * 1024 * 1024)
    state = {"dropped": False}

    def on_progress(idx, sent):
        if not state["dropped"] and sent > 600_000:
            state["dropped"] = True
            raise ConnectionResetError("simüle kopma")

    srv = core.FileServer(lambda k: True, lambda: [core.SharedFile(src)], on_progress)
    srv.progress_interval = 0
    port = srv.start()
    part = os.path.join(tmp, "q", "d.part")
    retries = []
    ok = core.fetch(lambda: "http://127.0.0.1:%d/file/0?k=x" % port, part, os.path.getsize(src),
                    lambda g: None, lambda: False, retries.append)
    assert state["dropped"] and retries, "kopma olmadı"
    assert ok and sha(part) == sha(src)
    srv.stop()
    print("ok mid-stream drop, retries:", retries)


def test_sender_receiver(tmp):
    a = make_file(tmp, "a.txt", 1000)
    b = make_file(tmp, "b ö.bin", 700_000)
    s = core.Sender([a, b])
    s.start()
    s.link.hosts = ["127.0.0.1"]  # sandbox'ta özel IP olmayabilir
    link = core.Link.parse(s.link.encode())
    dest = os.path.join(tmp, "dest")
    r = core.Receiver(link, dest, os.path.join(tmp, "work"))
    r.run()
    assert r.state.phase == "done", (r.state.phase, r.state.message)
    assert sha(os.path.join(dest, "a.txt")) == sha(a)
    assert sha(os.path.join(dest, "b ö.bin")) == sha(b)
    assert r.state.percent == 100
    wait(lambda: s.state.phase == "done", 3, "gönderen done")
    s.stop()
    print("ok sender/receiver 1:1")


def test_pool(tmp):
    pa, pb, pc = 47601, 47602, 47603
    key = core.new_key()
    kw = dict(every=0.3)
    A = core.PoolEngine(key, os.path.join(tmp, "dA"), "idA", "Telefon", os.path.join(tmp, "wA"),
                        udp_port=pa, targets=[("127.0.0.1", pb), ("127.0.0.1", pc)], **kw)
    B = core.PoolEngine(key, os.path.join(tmp, "dB"), "idB", "Tablet", os.path.join(tmp, "wB"),
                        udp_port=pb, targets=[("127.0.0.1", pa), ("127.0.0.1", pc)], **kw)
    C = core.PoolEngine(core.new_key(), os.path.join(tmp, "dC"), "idC", "Yabancı", os.path.join(tmp, "wC"),
                        udp_port=pc, targets=[("127.0.0.1", pa), ("127.0.0.1", pb)], **kw)
    for e in (A, B, C):
        e.start()
    f1 = make_file(tmp, "havuz1.bin", 1_500_000)
    f2 = make_file(tmp, "havuz2.txt", 50)
    A.add_files([f1, f2])
    wait(lambda: any(d["files"] for d in B.snapshot()["devices"]), 8, "B, A'nın dosyalarını görsün")
    snap = B.snapshot()
    assert [d["name"] for d in snap["devices"]] == ["Telefon"], snap
    assert [f[1] for f in snap["devices"][0]["files"]] == ["havuz1.bin", "havuz2.txt"]
    assert C.snapshot()["devices"] == [], "farklı havuz görünmemeli"
    # B'den A'ya da görünürlük
    B.add_files([make_file(tmp, "tablet.dat", 4096)])
    wait(lambda: any(d["files"] for d in A.snapshot()["devices"]), 8, "A, B'nin dosyasını görsün")
    # indirme
    B.download("idA", 0)
    wait(lambda: B.snapshot()["dl"].get(("idA", 0), {}).get("status") == "done", 10, "indirme")
    got = B.snapshot()["dl"][("idA", 0)]["path"]
    assert sha(got) == sha(f1)
    # kaldırma
    A.remove_file(0)
    wait(lambda: [f[1] for f in B.snapshot()["devices"][0]["files"]] == ["havuz2.txt"], 8, "kaldırma yansısın")
    # cihaz kapanınca listeden düşsün
    A.stop()
    wait(lambda: B.snapshot()["devices"] == [], 8, "ayrılan cihaz düşsün")
    for e in (B, C):
        e.stop()
    print("ok pool (3 motor, keşif + liste + indirme + kaldırma + ayrılma)")


def test_pool_restart(tmp):
    pa, pb = 47611, 47612
    key = core.new_key()
    saved = {}
    A = core.PoolEngine(key, os.path.join(tmp, "dA"), "idA", "PC", os.path.join(tmp, "wA"),
                        udp_port=pa, targets=[("127.0.0.1", pb)], every=0.3,
                        on_shared=lambda p: saved.__setitem__("p", p))
    B = core.PoolEngine(key, os.path.join(tmp, "dB"), "idB", "Tablet", os.path.join(tmp, "wB"),
                        udp_port=pb, targets=[("127.0.0.1", pa)], every=0.3)
    A.start()
    B.start()
    f1 = make_file(tmp, "a1.bin", 1000)
    f2 = make_file(tmp, "a2.bin", 2000)
    A.add_files([f1, f2])
    A.remove_file(0)
    wait(lambda: [f[1] for d in B.snapshot()["devices"] for f in d["files"]] == ["a2.bin"], 8, "ilk liste")
    assert saved["p"] == ["", f2], saved
    # uygulama kapanır, yeniden açılır: kayıtlı paylaşımlar geri gelir, indeks aynı kalır
    A.stop()
    wait(lambda: B.snapshot()["devices"] == [], 8, "A düşsün")
    A2 = core.PoolEngine(key, os.path.join(tmp, "dA"), "idA", "PC", os.path.join(tmp, "wA"),
                         udp_port=pa, targets=[("127.0.0.1", pb)], every=0.3, shared=saved["p"])
    assert A2.version != 0
    A2.start()
    wait(lambda: [(f[0], f[1]) for d in B.snapshot()["devices"] for f in d["files"]] == [(1, "a2.bin")], 8,
         "yeniden açılınca liste geri gelsin")
    B.download("idA", 1)
    wait(lambda: B.snapshot()["dl"].get(("idA", 1), {}).get("status") == "done", 10, "indirme")
    assert sha(B.snapshot()["dl"][("idA", 1)]["path"]) == sha(f2)
    A2.stop()
    B.stop()
    print("ok pool restart (paylaşımlar hatırlandı, indeks sabit)")


if __name__ == "__main__":
    root = tempfile.mkdtemp(prefix="hatest_")
    try:
        test_links()
        for fn in (test_server_resume, test_midstream_drop, test_sender_receiver, test_pool, test_pool_restart):
            d = os.path.join(root, fn.__name__)
            os.makedirs(d)
            fn(d)
        print("TÜM TESTLER GEÇTİ")
    finally:
        shutil.rmtree(root, ignore_errors=True)
