"""Hızlı Aktar - Windows arayüzü (tkinter)."""
import os
import sys
import traceback
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

import core

try:
    import qrcode
    import qrcode.constants
except ImportError:  # QR kütüphanesi yoksa kodu metin olarak göster
    qrcode = None

BG = "#0f172a"
ACCENT = "#0e7490"


def draw_qr(canvas, text, px=240):
    """QR'ı doğrudan Canvas'a çizer (Pillow gerekmez)."""
    canvas.delete("all")
    if qrcode is None:
        canvas.config(width=px, height=60)
        canvas.create_text(10, 30, anchor="w", text="QR kütüphanesi yok:\n" + text)
        return
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2, box_size=1)
    qr.add_data(text)
    qr.make(fit=True)
    m = qr.get_matrix()
    n = len(m)
    cell = max(1, px // n)
    size = cell * n
    canvas.config(width=size, height=size)
    canvas.create_rectangle(0, 0, size, size, fill="white", outline="")
    for y, row in enumerate(m):
        for x, v in enumerate(row):
            if v:
                canvas.create_rectangle(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell,
                                        fill="black", outline="")


def open_folder(path):
    try:
        os.makedirs(path, exist_ok=True)
        if hasattr(os, "startfile"):
            os.startfile(path)  # noqa: Windows
    except OSError:
        pass


class ProgressView(ttk.Frame):
    """Durum, yüzde, hız ve dosya başına ilerleme."""

    def __init__(self, parent):
        super().__init__(parent)
        self.meter = core.SpeedMeter()
        self.status = ttk.Label(self, text="", wraplength=700)
        self.status.pack(anchor="w", pady=(6, 2))
        self.pct = ttk.Label(self, text="%0", font=("Segoe UI", 20, "bold"))
        self.pct.pack(anchor="w")
        self.bar = ttk.Progressbar(self, maximum=1000)
        self.bar.pack(fill="x", pady=2)
        self.sub = ttk.Label(self, text="")
        self.sub.pack(anchor="w")
        self.files = tk.Listbox(self, height=6, activestyle="none")
        self.files.pack(fill="both", expand=True, pady=(6, 0))

    def render(self, st):
        self.status.config(text=st.message)
        self.pct.config(text="%" + str(st.percent))
        self.bar.config(value=(st.done * 1000 // st.total) if st.total > 0 else 0)
        speed = self.meter.update(st.done) if st.phase in ("transferring", "reconnecting") else 0
        self.sub.config(text="%s / %s   ·   %s" % (core.fmt_bytes(st.done), core.fmt_bytes(st.total),
                                                    core.fmt_speed(speed)))
        lines = []
        for name, size, done in list(st.files):
            p = 100 if size <= 0 else int(done * 100 / size)
            lines.append("%s   —   %%%d" % (name, p))
        if list(self.files.get(0, "end")) != lines:
            self.files.delete(0, "end")
            for ln in lines:
                self.files.insert("end", ln)


class PoolTab(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app
        self.engine = None
        self.top = ttk.Frame(self)
        self.top.pack(fill="x")
        self.btn_new = ttk.Button(self.top, text="Yeni havuz oluştur", command=self.new_pool)
        self.btn_join = ttk.Button(self.top, text="Koda / bağlantıya katıl…", command=self.join_pool)
        self.btn_leave = ttk.Button(self.top, text="Havuzdan çık", command=self.leave_pool)
        self.btn_new.pack(side="left", padx=(0, 6))
        self.btn_join.pack(side="left", padx=(0, 6))
        self.btn_leave.pack(side="left")

        self.info = ttk.Label(self, text="", wraplength=760, justify="left")
        self.info.pack(anchor="w", pady=(8, 4))

        self.code_frame = ttk.Frame(self)
        self.code_lbl = ttk.Label(self.code_frame, text="", font=("Consolas", 18, "bold"))
        self.code_lbl.pack(anchor="w")
        ttk.Label(self.code_frame, text="Telefon/tabletten QR'ı okut ya da kodu bir kez gir.").pack(anchor="w")
        self.qr = tk.Canvas(self.code_frame, width=10, height=10, highlightthickness=0)
        self.qr.pack(anchor="w", pady=4)

        cols = ("device", "file", "size", "state")
        self.tree = ttk.Treeview(self, columns=cols, show="headings", selectmode="extended", height=10)
        for c, t, w in (("device", "Cihaz", 140), ("file", "Dosya", 360), ("size", "Boyut", 90),
                        ("state", "Durum", 150)):
            self.tree.heading(c, text=t)
            self.tree.column(c, width=w, anchor="w")
        self.tree.bind("<Double-1>", lambda e: self.download_selected())
        self.tree.pack(fill="both", expand=True, pady=6)

        bar = ttk.Frame(self)
        bar.pack(fill="x")
        ttk.Button(bar, text="Dosya ekle…", command=self.add_files).pack(side="left", padx=(0, 6))
        ttk.Button(bar, text="Seçileni indir", command=self.download_selected).pack(side="left", padx=(0, 6))
        ttk.Button(bar, text="Paylaşımdan kaldır", command=self.remove_selected).pack(side="left")
        self.speed = ttk.Label(bar, text="")
        self.speed.pack(side="right")

        key = self.app.cfg.get("pool_key")
        if key:
            self.start_engine(key)
        else:
            self.update_buttons()

    # -- havuz yönetimi
    def start_engine(self, key):
        if self.engine:
            self.engine.stop()
        cfg = self.app.cfg
        self.engine = core.PoolEngine(key, cfg.get("dest_dir"), cfg.get("device_id"), cfg.get("device_name"))
        self.engine.start()
        cfg.set("pool_key", key)
        self.code_lbl.config(text="Havuz kodu: " + core.pretty_key(key))
        draw_qr(self.qr, core.pool_link(key), 200)
        self.update_buttons()

    def update_buttons(self):
        on = self.engine is not None
        self.btn_new.config(state="disabled" if on else "normal")
        self.btn_join.config(state="disabled" if on else "normal")
        self.btn_leave.config(state="normal" if on else "disabled")
        if on:
            self.code_frame.pack(anchor="w", before=self.tree)
        else:
            self.code_frame.pack_forget()
            self.info.config(text="Ortak havuz: aynı Wi-Fi/ağdaki cihazlar birbirinin paylaştığı dosyaları görür ve "
                                  "tıklayıp indirir. Bir cihazda 'Yeni havuz oluştur' de, diğerlerinde QR'ı okut "
                                  "ya da kodu gir. Bir kez bağlanman yeter.")

    def new_pool(self):
        self.start_engine(core.new_key())

    def join_pool(self):
        text = simpledialog.askstring("Havuza katıl", "Havuz kodunu yaz (ya da bağlantıyı yapıştır):", parent=self)
        if not text:
            return
        key = core.parse_pool(text)
        if key is None:
            messagebox.showerror("Hızlı Aktar", "Geçersiz havuz kodu.")
            return
        self.start_engine(key)

    def leave_pool(self):
        if self.engine:
            self.engine.stop()
        self.engine = None
        self.app.cfg.set("pool_key", None)
        for iid in self.tree.get_children():
            self.tree.delete(iid)
        self.update_buttons()

    def stop(self):
        if self.engine:
            self.engine.stop()

    # -- dosya işlemleri
    def add_files(self):
        if not self.engine:
            messagebox.showinfo("Hızlı Aktar", "Önce bir havuz oluştur ya da katıl.")
            return
        paths = filedialog.askopenfilenames(title="Paylaşılacak dosyalar")
        if paths:
            self.engine.add_files(list(paths))

    def _selected(self):
        out = []
        for iid in self.tree.selection():
            owner, _, idx = iid.rpartition("|")
            out.append((owner, int(idx)))
        return out

    def download_selected(self):
        if not self.engine:
            return
        for owner, idx in self._selected():
            if owner != "me":
                self.engine.download(owner, idx)

    def remove_selected(self):
        if not self.engine:
            return
        for owner, idx in self._selected():
            if owner == "me":
                self.engine.remove_file(idx)

    # -- görünüm
    def refresh(self):
        if not self.engine:
            return
        snap = self.engine.snapshot()
        rows = []
        for i, name, size in snap["mine"]:
            rows.append(("me|%d" % i, ("Benim", name, core.fmt_bytes(size), "Paylaşılıyor")))
        for d in snap["devices"]:
            for i, name, size in d["files"]:
                s = snap["dl"].get((d["id"], i))
                if s is None:
                    state = "İndirmek için çift tıkla"
                elif s["status"] == "downloading":
                    state = "İndiriliyor %%%d" % (int(s["done"] * 100 / s["size"]) if s["size"] else 100)
                elif s["status"] == "done":
                    state = "İndirildi ✓"
                else:
                    state = "Hata - tekrar dene"
                rows.append(("%s|%d" % (d["id"], i), (d["name"], name, core.fmt_bytes(size), state)))
        wanted = {iid for iid, _ in rows}
        for iid in self.tree.get_children():
            if iid not in wanted:
                self.tree.delete(iid)
        have = set(self.tree.get_children())
        for iid, vals in rows:
            if iid in have:
                self.tree.item(iid, values=vals)
            else:
                self.tree.insert("", "end", iid=iid, values=vals)
        n = len(snap["devices"])
        msg = "Havuz açık · %d cihaz bağlı" % n if n else "Havuz açık · henüz başka cihaz görünmüyor (aynı ağda mısınız?)"
        if snap["error"]:
            msg += " · " + snap["error"]
        self.info.config(text=msg)
        self.speed.config(text=core.fmt_speed(snap["speed"]))


class SendTab(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app
        self.paths = []
        self.sender = None
        bar = ttk.Frame(self)
        bar.pack(fill="x")
        ttk.Button(bar, text="Dosya seç…", command=self.pick).pack(side="left", padx=(0, 6))
        self.btn_start = ttk.Button(bar, text="Başlat", command=self.start)
        self.btn_start.pack(side="left", padx=(0, 6))
        self.btn_stop = ttk.Button(bar, text="Durdur", command=self.stop, state="disabled")
        self.btn_stop.pack(side="left")
        self.info = ttk.Label(self, text="Dosya seç, Başlat'a bas; alıcı telefonda 'Al' → QR'ı okutsun "
                                         "(iki cihaz aynı Wi-Fi'da olmalı).", wraplength=760)
        self.info.pack(anchor="w", pady=6)
        self.qr = tk.Canvas(self, width=10, height=10, highlightthickness=0)
        self.qr.pack(anchor="w")
        self.view = ProgressView(self)
        self.view.pack(fill="both", expand=True)

    def pick(self):
        if self.sender:
            return
        p = filedialog.askopenfilenames(title="Gönderilecek dosyalar")
        if p:
            self.paths = list(p)
            total = sum(os.path.getsize(x) for x in self.paths)
            self.info.config(text="%d dosya seçildi · %s" % (len(self.paths), core.fmt_bytes(total)))

    def start(self):
        if not self.paths:
            messagebox.showinfo("Hızlı Aktar", "Önce dosya seç.")
            return
        if self.sender:
            return
        self.sender = core.Sender(self.paths)
        self.sender.start()
        draw_qr(self.qr, self.sender.state.qr, 260)
        self.btn_start.config(state="disabled")
        self.btn_stop.config(state="normal")

    def stop(self):
        if self.sender:
            self.sender.stop()
        self.sender = None
        self.qr.delete("all")
        self.qr.config(width=10, height=10)
        self.btn_start.config(state="normal")
        self.btn_stop.config(state="disabled")

    def refresh(self):
        if self.sender:
            self.view.render(self.sender.state)


class ReceiveTab(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app
        self.receiver = None
        ttk.Label(self, text="Gönderen cihazın bağlantısını (hizli://…) yapıştır. Telefondan alıyorsan: "
                             "telefonda Gönder'i aç, bağlantıyı kopyala/paylaş ve buraya yapıştır.",
                  wraplength=760).pack(anchor="w")
        row = ttk.Frame(self)
        row.pack(fill="x", pady=6)
        self.var = tk.StringVar()
        ttk.Entry(row, textvariable=self.var).pack(side="left", fill="x", expand=True, padx=(0, 6))
        ttk.Button(row, text="Panodan yapıştır", command=self.paste).pack(side="left", padx=(0, 6))
        self.btn = ttk.Button(row, text="Bağlan", command=self.connect)
        self.btn.pack(side="left", padx=(0, 6))
        self.btn_cancel = ttk.Button(row, text="İptal", command=self.cancel, state="disabled")
        self.btn_cancel.pack(side="left")
        self.view = ProgressView(self)
        self.view.pack(fill="both", expand=True)

    def paste(self):
        try:
            self.var.set(self.clipboard_get().strip())
        except tk.TclError:
            pass

    def connect(self):
        text = self.var.get().strip()
        if text.startswith("hizli://pool") or core.parse_pool(text):
            key = core.parse_pool(text)
            if key:
                self.app.join_pool_key(key)
                return
        link = core.Link.parse(text)
        if link is None:
            messagebox.showerror("Hızlı Aktar", "Geçersiz bağlantı.")
            return
        self.view.meter = core.SpeedMeter()
        self.receiver = core.Receiver(link, self.app.cfg.get("dest_dir"))
        self.receiver.start()
        self.btn.config(state="disabled")
        self.btn_cancel.config(state="normal")

    def cancel(self):
        if self.receiver:
            self.receiver.cancel()
        self.receiver = None
        self.btn.config(state="normal")
        self.btn_cancel.config(state="disabled")

    def refresh(self):
        if not self.receiver:
            return
        st = self.receiver.state
        self.view.render(st)
        if st.phase in ("done", "error"):
            self.btn.config(state="normal")
            self.btn_cancel.config(state="disabled")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Hızlı Aktar")
        self.geometry("860x700")
        self.minsize(760, 600)
        self.cfg = core.Config()
        try:
            ttk.Style().theme_use("vista")
        except tk.TclError:
            pass
        ttk.Style().configure("Treeview", rowheight=24)
        self.nb = ttk.Notebook(self)
        self.nb.pack(fill="both", expand=True, padx=8, pady=(8, 0))
        self.pool_tab = PoolTab(self.nb, self)
        self.send_tab = SendTab(self.nb, self)
        self.recv_tab = ReceiveTab(self.nb, self)
        self.nb.add(self.pool_tab, text="  Ortak Havuz  ")
        self.nb.add(self.send_tab, text="  Gönder (QR)  ")
        self.nb.add(self.recv_tab, text="  Al  ")

        foot = ttk.Frame(self)
        foot.pack(fill="x", padx=10, pady=6)
        self.dest_lbl = ttk.Label(foot, text="")
        self.dest_lbl.pack(side="left")
        ttk.Button(foot, text="Aç", command=lambda: open_folder(self.cfg.get("dest_dir"))).pack(side="right")
        ttk.Button(foot, text="Klasörü değiştir", command=self.change_dest).pack(side="right", padx=6)
        self.show_dest()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(300, self.tick)

    def show_dest(self):
        self.dest_lbl.config(text="İndirilenler: " + self.cfg.get("dest_dir"))

    def change_dest(self):
        d = filedialog.askdirectory(title="İndirme klasörü")
        if d:
            self.cfg.set("dest_dir", d)
            if self.pool_tab.engine:
                self.pool_tab.engine.dest = d
            self.show_dest()

    def join_pool_key(self, key):
        self.nb.select(self.pool_tab)
        self.pool_tab.start_engine(key)

    def tick(self):
        for tab in (self.pool_tab, self.send_tab, self.recv_tab):
            try:
                tab.refresh()
            except Exception:  # arayüz asla çökmesin
                traceback.print_exc()
        self.after(300, self.tick)

    def on_close(self):
        self.pool_tab.stop()
        if self.send_tab.sender:
            self.send_tab.sender.stop()
        self.destroy()


def main():
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except (OSError, AttributeError):
            pass
    App().mainloop()


if __name__ == "__main__":
    main()
