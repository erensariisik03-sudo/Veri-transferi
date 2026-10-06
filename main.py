import os
import time
import random
import threading
import urllib.request
import urllib.parse

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics.texture import Texture
from kivy.lang import Builder
from kivy.metrics import dp
from kivy.properties import StringProperty, NumericProperty
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.utils import platform

import core

DOWNLOAD_DIR = "/sdcard/Download" if platform == "android" else os.path.expanduser("~/Downloads")
START_DIR = DOWNLOAD_DIR if os.path.isdir(DOWNLOAD_DIR) else os.path.expanduser("~")


# ----------------------------------------------------------------------
#  Android: izinler ve QR tarayıcı (ZXing)
# ----------------------------------------------------------------------
def request_storage_permissions():
    if platform != "android":
        return
    try:
        from android.permissions import request_permissions, Permission
        request_permissions([Permission.READ_EXTERNAL_STORAGE, Permission.WRITE_EXTERNAL_STORAGE])
    except Exception as e:
        print("İzin hatası:", e)
    try:
        from jnius import autoclass
        if autoclass("android.os.Build$VERSION").SDK_INT >= 30:
            Environment = autoclass("android.os.Environment")
            if not Environment.isExternalStorageManager():
                Intent = autoclass("android.content.Intent")
                Settings = autoclass("android.provider.Settings")
                Uri = autoclass("android.net.Uri")
                activity = autoclass("org.kivy.android.PythonActivity").mActivity
                intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.setData(Uri.parse("package:" + activity.getPackageName()))
                activity.startActivity(intent)
    except Exception as e:
        print("Tüm dosyalara erişim izni hatası:", e)


def scan_qr(callback):
    """QR tarar. callback(metin_veya_None, hata_veya_None) ana thread'de çağrılır."""
    def done(text, err=None):
        Clock.schedule_once(lambda dt: callback(text, err))

    if platform != "android":
        done(None, "QR tarama yalnızca Android'de çalışır. Bağlantıyı elle yapıştırın.")
        return
    try:
        from android.permissions import request_permissions, Permission

        def after(perms, grants):
            if grants and all(grants):
                _launch_scanner(done)
            else:
                done(None, "Kamera izni verilmedi.")

        request_permissions([Permission.CAMERA], after)
    except Exception as e:
        done(None, f"Tarayıcı başlatılamadı: {e}")


def _launch_scanner(done):
    try:
        from android.runnable import run_on_ui_thread
        from android import activity
        from jnius import autoclass

        IntentIntegrator = autoclass("com.google.zxing.integration.android.IntentIntegrator")
        PythonActivity = autoclass("org.kivy.android.PythonActivity")

        def on_result(request_code, result_code, intent):
            if request_code != IntentIntegrator.REQUEST_CODE:
                return
            activity.unbind(on_activity_result=on_result)
            text = None
            try:
                res = IntentIntegrator.parseActivityResult(request_code, result_code, intent)
                text = res.getContents() if res else None
            except Exception as e:
                done(None, f"QR okunamadı: {e}")
                return
            done(text, None if text else "Tarama iptal edildi.")

        @run_on_ui_thread
        def start():
            integ = IntentIntegrator(PythonActivity.mActivity)
            integ.setPrompt("Göndericideki QR kodu okutun")
            integ.setBeepEnabled(False)
            integ.initiateScan()

        activity.bind(on_activity_result=on_result)
        start()
    except Exception as e:
        done(None, f"Tarayıcı başlatılamadı: {e}")


# ----------------------------------------------------------------------
#  Arayüz tanımı
# ----------------------------------------------------------------------
KV = """
#:import dp kivy.metrics.dp

<BigButton@Button>:
    size_hint_y: None
    height: dp(56)
    font_size: '18sp'
    background_normal: ''
    background_color: 0, 0.78, 0.45, 1
    color: 0, 0, 0, 1
    bold: True

<GrayButton@BigButton>:
    background_color: 0.3, 0.3, 0.3, 1
    color: 1, 1, 1, 1

<Title@Label>:
    font_size: '22sp'
    bold: True
    size_hint_y: None
    height: dp(48)

<ItemButton@Button>:
    size_hint_y: None
    height: dp(52)
    background_normal: ''
    background_color: 0.15, 0.15, 0.15, 1
    halign: 'left'
    valign: 'middle'
    shorten: True
    shorten_from: 'right'
    text_size: self.width - dp(24), self.height

<MenuScreen>:
    BoxLayout:
        orientation: 'vertical'
        padding: dp(24)
        spacing: dp(16)
        Title:
            text: 'LAN DOSYA TRANSFERİ'
        Label:
            text: 'Aynı Wi-Fi / hotspot ağındaki cihazlar arasında QR ile dosya aktarımı'
            text_size: self.width, None
            halign: 'center'
            size_hint_y: None
            height: dp(60)
        BigButton:
            text: 'Dosya Gönder'
            on_release: app.open_browser()
        BigButton:
            text: 'Dosya Al (QR Tara)'
            on_release: app.open_receiver()
        Widget:

<BrowseScreen>:
    BoxLayout:
        orientation: 'vertical'
        padding: dp(10)
        spacing: dp(6)
        Title:
            text: 'Gönderilecek Dosyayı Seç'
        Label:
            text: root.path_text
            font_size: '13sp'
            size_hint_y: None
            height: dp(24)
            text_size: self.width, None
            halign: 'left'
            shorten: True
        ScrollView:
            BoxLayout:
                id: items
                orientation: 'vertical'
                size_hint_y: None
                height: self.minimum_height
                spacing: dp(3)
        Label:
            text: root.message
            color: 1, 0.4, 0.4, 1
            size_hint_y: None
            height: dp(28)
        GrayButton:
            text: 'Geri'
            on_release: app.go('menu')

<ShareScreen>:
    BoxLayout:
        orientation: 'vertical'
        padding: dp(14)
        spacing: dp(8)
        Title:
            text: 'QR Kodu Karşı Cihaza Okutun'
        Image:
            id: qr
            fit_mode: 'contain'
        Label:
            text: root.file_text
            size_hint_y: None
            height: dp(36)
            text_size: self.width, None
            halign: 'center'
            shorten: True
        Label:
            text: root.url_text
            font_size: '12sp'
            color: 0.7, 0.7, 0.7, 1
            size_hint_y: None
            height: dp(22)
        ProgressBar:
            max: 100
            value: root.progress
            size_hint_y: None
            height: dp(20)
        Label:
            text: root.status_text
            font_size: '17sp'
            size_hint_y: None
            height: dp(36)
        GrayButton:
            text: 'Durdur ve Geri Dön'
            on_release: root.stop()

<ReceiverScreen>:
    BoxLayout:
        orientation: 'vertical'
        padding: dp(16)
        spacing: dp(10)
        Title:
            text: 'Dosya Al'
        BigButton:
            text: 'QR Kodu Tara'
            on_release: root.scan()
        Label:
            text: 'veya bağlantıyı elle yapıştır:'
            size_hint_y: None
            height: dp(28)
        TextInput:
            id: url
            hint_text: 'http://192.168.1.35:8080/d/...'
            multiline: False
            size_hint_y: None
            height: dp(48)
        GrayButton:
            text: 'Bağlantıdan İndir'
            on_release: root.start_download(url.text)
        ProgressBar:
            max: 100
            value: root.progress
            size_hint_y: None
            height: dp(20)
        Label:
            text: root.status
            text_size: self.width, None
            halign: 'center'
            valign: 'middle'
        GrayButton:
            text: 'Geri'
            on_release: app.go('menu')
"""


class MenuScreen(Screen):
    pass


class BrowseScreen(Screen):
    path_text = StringProperty("")
    message = StringProperty("")
    current = START_DIR

    def open_dir(self, path):
        from kivy.factory import Factory
        self.message = ""
        try:
            names = os.listdir(path)
        except PermissionError:
            self.message = "Bu klasöre erişim izni yok (Tüm dosyalara erişim iznini verin)."
            return
        except OSError as e:
            self.message = f"Klasör açılamadı: {e}"
            return
        self.current = path
        self.path_text = path
        box = self.ids["items"]
        box.clear_widgets()

        def add(text, cb):
            b = Factory.ItemButton(text=text)
            b.bind(on_release=lambda *_: cb())
            box.add_widget(b)

        parent = os.path.dirname(path.rstrip("/")) or "/"
        if parent != path:
            add("..  (üst klasör)", lambda: self.open_dir(parent))

        dirs, files = [], []
        for n in names:
            if n.startswith("."):
                continue
            full = os.path.join(path, n)
            (dirs if os.path.isdir(full) else files).append(n)
        for n in sorted(dirs, key=str.lower):
            add(f"[Klasör]  {n}", lambda n=n: self.open_dir(os.path.join(path, n)))
        for n in sorted(files, key=str.lower):
            full = os.path.join(path, n)
            try:
                size = core.human_size(os.path.getsize(full))
            except OSError:
                size = "?"
            add(f"{n}   ({size})", lambda full=full: App.get_running_app().share(full))
        if not dirs and not files:
            self.message = "Bu klasör boş."


class ShareScreen(Screen):
    file_text = StringProperty("")
    url_text = StringProperty("")
    status_text = StringProperty("")
    progress = NumericProperty(0)
    server = None
    _poll = None

    def begin(self, path):
        self.stop(go_back=False)
        port = core.get_free_port(8080)
        token = core.reset_share(path)
        try:
            self.server = core.ServerThread(port)
            self.server.start()
        except Exception as e:
            self.server = None
            core.clear_share()
            self.status_text = f"Sunucu başlatılamadı: {e}"
            return
        url = f"http://{core.get_local_ip()}:{port}/d/{token}"
        size, buf = core.make_qr_buffer(url)
        tex = Texture.create(size=(size, size), colorfmt="rgb")
        tex.blit_buffer(buf, colorfmt="rgb", bufferfmt="ubyte")
        tex.mag_filter = "nearest"
        tex.min_filter = "nearest"
        tex.flip_vertical()
        self.ids["qr"].texture = tex
        self.file_text = f"{os.path.basename(path)}  ({core.human_size(core.STATE['total'])})"
        self.url_text = url.rsplit("/d/", 1)[0]
        self.progress = 0
        self.status_text = "Karşı cihazın QR'ı okutmasını bekliyor..."
        Window.allow_screensaver = False
        self._poll = Clock.schedule_interval(self.update, 0.2)

    def update(self, dt):
        st = core.STATE
        total = max(st["total"], 1)
        sent = st["sent"]
        pct = min(100, sent * 100 / total)
        self.progress = pct
        if st["status"] == "sending":
            self.status_text = f"Gönderiliyor  %{pct:.0f}   ({core.human_size(sent)} / {core.human_size(st['total'])})"
        elif st["status"] == "done":
            self.progress = 100
            self.status_text = "Gönderim tamamlandı!"
        elif st["status"] == "aborted":
            self.status_text = "Bağlantı kesildi. Yeniden QR okutabilir."
        elif st["status"] == "waiting":
            self.status_text = "Karşı cihazın QR'ı okutmasını bekliyor..."

    def stop(self, go_back=True):
        if self._poll:
            self._poll.cancel()
            self._poll = None
        if self.server:
            try:
                self.server.stop()
            except Exception:
                pass
            self.server = None
        core.clear_share()
        Window.allow_screensaver = True
        if go_back:
            App.get_running_app().go("menu")


class ReceiverScreen(Screen):
    status = StringProperty("")
    progress = NumericProperty(0)
    busy = False

    def scan(self):
        if self.busy:
            return
        self.status = "Tarayıcı açılıyor..."
        scan_qr(self._on_scan)

    def _on_scan(self, text, err):
        if err or not text:
            self.status = err or "QR okunamadı."
            return
        self.start_download(text)

    def start_download(self, url):
        url = url.strip()
        if self.busy:
            return
        if not url.startswith(("http://", "https://")) or "/d/" not in url:
            self.status = "Geçersiz bağlantı / QR kodu."
            return
        self.busy = True
        self.progress = 0
        self.status = "Bağlanıyor..."
        threading.Thread(target=self._worker, args=(url,), daemon=True).start()

    def _ui(self, status=None, progress=None):
        def apply(dt):
            if status is not None:
                self.status = status
            if progress is not None:
                self.progress = progress
        Clock.schedule_once(apply)

    def _worker(self, url):
        try:
            os.makedirs(DOWNLOAD_DIR, exist_ok=True)
            resp = urllib.request.urlopen(url, timeout=15)
            name = core.filename_from_disposition(resp.headers.get("Content-Disposition"))
            name = name or f"indirilen_{random.randint(100, 999)}.dat"
            save_path = core.unique_path(DOWNLOAD_DIR, name)
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            last = 0.0
            with open(save_path, "wb") as f:
                while True:
                    chunk = resp.read(64 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
                    done += len(chunk)
                    now = time.time()
                    if total and now - last > 0.15:
                        last = now
                        pct = done * 100 / total
                        self._ui(f"İndiriliyor  %{pct:.0f}   ({core.human_size(done)} / {core.human_size(total)})", pct)
            self._ui(f"İndirme tamamlandı!\n{save_path}", 100)
        except Exception as e:
            self._ui(f"Bağlantı başarısız. Aynı ağda mısınız, gönderici açık mı?\n{e}", 0)
        finally:
            self.busy = False


class LanTransferApp(App):
    title = "LAN Dosya Transferi"

    def build(self):
        Builder.load_string(KV)
        self.sm = ScreenManager()
        self.sm.add_widget(MenuScreen(name="menu"))
        self.sm.add_widget(BrowseScreen(name="browse"))
        self.sm.add_widget(ShareScreen(name="share"))
        self.sm.add_widget(ReceiverScreen(name="receiver"))
        return self.sm

    def on_start(self):
        request_storage_permissions()

    def go(self, name):
        self.sm.current = name

    def open_browser(self):
        scr = self.sm.get_screen("browse")
        scr.open_dir(scr.current if os.path.isdir(scr.current) else START_DIR)
        self.go("browse")

    def open_receiver(self):
        self.go("receiver")
        self.sm.get_screen("receiver").scan()

    def share(self, path):
        self.sm.get_screen("share").begin(path)
        self.go("share")

    def on_pause(self):
        return True

    def on_resume(self):
        pass


if __name__ == "__main__":
    LanTransferApp().run()
