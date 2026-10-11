"""Espelha o app rodando no emulador Android numa página em http://localhost:3000,
para ver e usar o Universal Media Tools dentro da extensão MobileView do VS Code
(ou em qualquer navegador).

    python tools/mobileview.py              # liga o emulador (sem janela), instala o APK de debug e serve na porta 3000
    python tools/mobileview.py --build      # roda ./gradlew assembleDebug antes de instalar
    python tools/mobileview.py --port 4000  # outra porta
    python tools/mobileview.py --no-install # só espelha o que já está instalado

O app é nativo (Kotlin/Compose), então não existe "versão web": a página mostra a
tela do emulador (AVD umt_test) e repassa toques, arrastos, rolagem e teclado pelo adb.
Ctrl+C encerra o servidor; o emulador continua ligado (adb emu kill para desligar).

Requer o Android SDK (ANDROID_HOME ou %LOCALAPPDATA%\\Android\\Sdk) e o Pillow.
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import queue
import os
import shutil
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.alabypedro.mediatools"
ACTIVITY = f"{PACKAGE}/.ui.MainActivity"
AVD = "umt_test"
# Largura da imagem enviada à página: suficiente para a moldura do MobileView e leve para atualizar rápido
FRAME_WIDTH = 540
NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW: não abre console extra no Windows


def sdk_dir() -> Path:
    for candidate in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                      str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")):
        if candidate and Path(candidate).is_dir():
            return Path(candidate)
    sys.exit("[ERRO] Android SDK não encontrado (defina ANDROID_HOME).")


SDK = sdk_dir()
ADB = str(SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
EMULATOR = str(SDK / "emulator" / ("emulator.exe" if os.name == "nt" else "emulator"))


def adb(*args: str, timeout: float = 30, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout, check=check, creationflags=NO_WINDOW)


def shell(*args: str, timeout: float = 30) -> str:
    return adb("shell", *args, timeout=timeout).stdout.decode(errors="replace").strip()


# ---------------------------------------------------------------- emulador e app

def ensure_device() -> None:
    """Usa um aparelho/emulador já conectado; senão liga o AVD sem janela e espera o boot."""
    devices = [l for l in adb("devices").stdout.decode().splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        print(f"Ligando o emulador {AVD} (sem janela)...", flush=True)
        subprocess.Popen([EMULATOR, "-avd", AVD, "-no-window", "-no-audio", "-no-boot-anim",
                          "-gpu", "swiftshader_indirect"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         creationflags=NO_WINDOW)
        adb("wait-for-device", timeout=300)
    for _ in range(120):
        if shell("getprop", "sys.boot_completed") == "1":
            break
        time.sleep(2)
    else:
        sys.exit("[ERRO] O Android não terminou de iniciar.")
    shell("settings", "put", "global", "stay_on_while_plugged_in", "7")  # tela não apaga
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")


def device_abi() -> str:
    return shell("getprop", "ro.product.cpu.abi") or "x86_64"


def install_and_launch(build: bool) -> None:
    if build:
        print("Compilando (assembleDebug)...", flush=True)
        gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        env = dict(os.environ)
        if not env.get("JAVA_HOME"):
            jdks = sorted((Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Eclipse Adoptium").glob("jdk-17*"))
            if jdks:
                env["JAVA_HOME"] = str(jdks[-1])
        if subprocess.run([gradlew, "assembleDebug", "-q"], cwd=ROOT, env=env).returncode != 0:
            sys.exit("[ERRO] O build falhou.")
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / f"app-{device_abi()}-debug.apk"
    if not apk.is_file():
        sys.exit(f"[ERRO] {apk.relative_to(ROOT)} não existe. Rode com --build.")
    print(f"Instalando {apk.name}...", flush=True)
    result = adb("install", "-r", "-g", str(apk), timeout=180)
    if b"INSTALL_FAILED_UPDATE_INCOMPATIBLE" in result.stdout + result.stderr:
        # instalado com outra assinatura (ex.: o APK de release): troca pelo de debug
        adb("uninstall", PACKAGE, timeout=60)
        result = adb("install", "-g", str(apk), timeout=180)
    if b"Success" not in result.stdout:
        sys.exit("[ERRO] Falha ao instalar: " + (result.stdout + result.stderr).decode(errors="replace").strip())
    launch()


def launch() -> None:
    shell("am", "start", "-n", ACTIVITY)


# ---------------------------------------------------------------- captura da tela

class Screen:
    """Captura a tela em segundo plano (só enquanto alguém está olhando) e guarda o último JPEG."""

    def __init__(self) -> None:
        self.size = (1080, 2400)
        self.jpeg = b""
        self.frame_id = 0
        self.last_request = 0.0
        self.lock = threading.Condition()
        self.newest_capture = 0.0
        # duas capturas intercaladas: enquanto uma transfere, a outra já captura
        for i in range(2):
            threading.Thread(target=self._loop, args=(i * 0.15,), daemon=True).start()

    def _grab(self) -> Image.Image:
        # screencap sem -p: pixels RGBA crus (mais rápido que PNG), comprimidos no aparelho
        # (transferir ~10 MB crus pelo adb é o gargalo). Cabeçalho: largura, altura, formato[, espaço de cor]
        raw = gzip.decompress(adb("exec-out", "screencap | gzip -1", timeout=10).stdout)
        w, h, _fmt = struct.unpack_from("<III", raw, 0)
        header = len(raw) - w * h * 4
        if header not in (12, 16) or w == 0:
            raise RuntimeError("captura inválida")
        return Image.frombuffer("RGBA", (w, h), raw, "raw", "RGBA", 0, 1).copy()

    def _loop(self, delay: float) -> None:
        time.sleep(delay)
        while True:
            if time.time() - self.last_request > 5:
                time.sleep(0.2)
                continue
            try:
                started = time.time()
                img = self._grab()
                self.size = img.size
                img = img.convert("RGB")
                img.thumbnail((FRAME_WIDTH, FRAME_WIDTH * 4), Image.BILINEAR)
                buf = io.BytesIO()
                img.save(buf, "JPEG", quality=80)
                with self.lock:
                    if started < self.newest_capture:
                        continue  # a outra captura já entregou uma tela mais nova
                    self.newest_capture = started
                    if buf.getvalue() != self.jpeg:
                        self.jpeg = buf.getvalue()
                        self.frame_id += 1
                    self.lock.notify_all()
            except Exception as exc:  # emulador reiniciando, rotação...
                print(f"[aviso] captura: {exc}", flush=True)
                time.sleep(1)

    def next_frame(self, after: int, timeout: float = 2.0) -> tuple[int, bytes]:
        """Espera um quadro mais novo que `after` (long polling) e devolve (id, jpeg)."""
        self.last_request = time.time()
        with self.lock:
            self.lock.wait_for(lambda: self.frame_id > after and self.jpeg, timeout=timeout)
            return self.frame_id, self.jpeg


SCREEN = Screen()
# eventos de toque/teclado em ordem (texto antes do Enter, por exemplo)
INPUTS: queue.Queue[dict] = queue.Queue()


def send_text(text: str) -> None:
    # `input text` não aceita espaço literal nem vários símbolos sem escape
    special = set("()<>|;&*\\~\"'`$?!#[]{}")
    out = []
    for ch in text:
        if ch == " ":
            out.append("%s")
        elif ch in special:
            out.append("\\" + ch)
        elif ord(ch) < 128:
            out.append(ch)
    if out:
        shell("input", "text", "".join(out))


KEYS = {"Enter": "66", "Backspace": "67", "Delete": "112", "Tab": "61", "Escape": "4",
        "ArrowUp": "19", "ArrowDown": "20", "ArrowLeft": "21", "ArrowRight": "22",
        "back": "4", "home": "3", "recents": "187"}


def handle_input(event: dict) -> None:
    w, h = SCREEN.size
    kind = event.get("type")
    if kind == "tap":
        shell("input", "tap", str(int(event["x"] * w)), str(int(event["y"] * h)))
    elif kind == "swipe":
        shell("input", "swipe", str(int(event["x1"] * w)), str(int(event["y1"] * h)),
              str(int(event["x2"] * w)), str(int(event["y2"] * h)), str(int(event.get("ms", 200))))
    elif kind == "longpress":
        x, y = str(int(event["x"] * w)), str(int(event["y"] * h))
        shell("input", "swipe", x, y, x, y, "700")
    elif kind == "text":
        send_text(event.get("text", ""))
    elif kind == "key" and event.get("key") in KEYS:
        shell("input", "keyevent", KEYS[event["key"]])
    elif kind == "launch":
        launch()


def input_worker() -> None:
    while True:
        event = INPUTS.get()
        try:
            handle_input(event)
        except Exception as exc:
            print(f"[aviso] entrada {event.get('type')}: {exc}", flush=True)


threading.Thread(target=input_worker, daemon=True).start()


# ---------------------------------------------------------------- página

PAGE = """<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Universal Media Tools (emulador)</title>
<style>
  :root { color-scheme: dark; }
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; touch-action: none;
               font: 13px system-ui, sans-serif; color: #ccc; user-select: none; -webkit-user-select: none; }
  #wrap { position: fixed; inset: 0 0 40px 0; display: flex; align-items: center; justify-content: center; }
  #screen { max-width: 100%; max-height: 100%; display: block; cursor: pointer; }
  #bar { position: fixed; left: 0; right: 0; bottom: 0; height: 40px; display: flex; background: #111; }
  #bar button { flex: 1; background: none; border: 0; color: #ddd; font-size: 18px; cursor: pointer; }
  #bar button:active { background: #222; }
  #msg { position: fixed; top: 45%; width: 100%; text-align: center; }
</style></head>
<body>
<div id="msg">Conectando ao emulador...</div>
<div id="wrap"><img id="screen" alt="" draggable="false"></div>
<div id="bar">
  <button data-key="back" title="Voltar (Esc)">◀</button>
  <button data-key="home" title="Início">●</button>
  <button data-key="recents" title="Apps recentes">■</button>
  <button id="relaunch" title="Abrir o Universal Media Tools">⟳</button>
</div>
<script>
const img = document.getElementById("screen"), msg = document.getElementById("msg");
// um evento por vez, na ordem em que aconteceram
let chain = Promise.resolve();
const post = (e) => { chain = chain.then(() => fetch("/input", {method: "POST", body: JSON.stringify(e)})).catch(() => {}); };
let frame = 0;
async function loop() {
  while (true) {
    try {
      const r = await fetch("/frame?after=" + frame, {cache: "no-store"});
      if (r.status === 200) {
        frame = +r.headers.get("X-Frame");
        const url = URL.createObjectURL(await r.blob());
        img.onload = () => URL.revokeObjectURL(url);
        img.src = url; msg.style.display = "none";
      }
    } catch (e) { msg.style.display = ""; msg.textContent = "Servidor desconectado"; await new Promise(r => setTimeout(r, 1000)); }
  }
}
loop();

// toque/clique, arrasto e toque longo (posições relativas 0..1)
const rel = (ev) => { const b = img.getBoundingClientRect(); return [(ev.clientX - b.left) / b.width, (ev.clientY - b.top) / b.height]; };
let start = null;
img.addEventListener("pointerdown", (ev) => { start = {p: rel(ev), t: Date.now()}; img.setPointerCapture(ev.pointerId); ev.preventDefault(); });
img.addEventListener("pointerup", (ev) => {
  if (!start) return;
  const [x2, y2] = rel(ev), [x1, y1] = start.p, ms = Date.now() - start.t;
  const moved = Math.hypot((x2 - x1) * img.width, (y2 - y1) * img.height) > 10;
  if (moved) post({type: "swipe", x1, y1, x2, y2, ms: Math.max(120, Math.min(ms, 1500))});
  else if (ms > 500) post({type: "longpress", x: x1, y: y1});
  else post({type: "tap", x: x1, y: y1});
  start = null;
});
// rolagem do mouse vira arrasto na tela
let wheelBusy = false;
img.addEventListener("wheel", (ev) => {
  ev.preventDefault(); if (wheelBusy) return; wheelBusy = true; setTimeout(() => wheelBusy = false, 250);
  const [x, y] = rel(ev), d = Math.sign(ev.deltaY) * 0.3;
  post({type: "swipe", x1: x, y1: Math.min(0.9, Math.max(0.1, y)), x2: x, y2: Math.min(0.95, Math.max(0.05, y - d)), ms: 200});
}, {passive: false});
// teclado: texto vai junto, teclas especiais uma a uma
let pending = "", timer = null;
document.addEventListener("keydown", (ev) => {
  if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
  if (ev.key.length === 1) {
    pending += ev.key; clearTimeout(timer);
    timer = setTimeout(() => { post({type: "text", text: pending}); pending = ""; }, 150);
  } else {
    // envia o texto que estava esperando antes da tecla (ex.: digitar e dar Enter)
    clearTimeout(timer);
    if (pending) { post({type: "text", text: pending}); pending = ""; }
    post({type: "key", key: ev.key});
  }
  ev.preventDefault();
});
document.querySelectorAll("[data-key]").forEach(b => b.onclick = () => post({type: "key", key: b.dataset.key}));
document.getElementById("relaunch").onclick = () => post({type: "launch"});
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args) -> None:  # silencioso: são várias requisições por segundo
        pass

    def do_GET(self) -> None:
        if self.path in ("/", "/index.html"):
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith("/frame"):
            try:
                after = int(self.path.split("after=", 1)[1]) if "after=" in self.path else 0
            except ValueError:
                after = 0
            fid, jpeg = SCREEN.next_frame(after)
            if fid <= after or not jpeg:
                self.send_response(204)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(jpeg)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame", str(fid))
            self.end_headers()
            self.wfile.write(jpeg)
        else:
            self.send_error(404)

    def do_POST(self) -> None:
        if self.path != "/input":
            self.send_error(404)
            return
        try:
            event = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            INPUTS.put(event)
            self.send_response(204)
        except (ValueError, KeyError):
            self.send_response(400)
        self.end_headers()


def main() -> int:
    parser = argparse.ArgumentParser(description="Espelha o app do emulador em http://localhost:PORTA (MobileView).")
    parser.add_argument("--port", type=int, default=3000)
    parser.add_argument("--build", action="store_true", help="compila o APK de debug antes de instalar")
    parser.add_argument("--no-install", action="store_true", help="não instala; só abre o app e espelha")
    args = parser.parse_args()

    if not shutil.which(ADB) and not Path(ADB).is_file():
        sys.exit(f"[ERRO] adb não encontrado em {ADB}")
    ensure_device()
    if args.no_install:
        launch()
    else:
        install_and_launch(args.build)

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"\n[OK] Universal Media Tools em http://localhost:{args.port}", flush=True)
    print("     No MobileView: cole essa URL. Ctrl+C encerra (o emulador continua ligado).", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
