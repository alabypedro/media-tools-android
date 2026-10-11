"""Publica uma versao nova do Universal Media Tools para Android com um comando so.

    python release.py "O que mudou"            # 0.2.0 -> 0.2.1 (correcao)
    python release.py --minor "O que mudou"    # 0.2.0 -> 0.3.0 (recurso novo)
    python release.py --major "O que mudou"    # 0.2.0 -> 1.0.0
    python release.py --version 0.4.2 "..."    # versao exata

O que ele faz, nesta ordem (para no primeiro erro):
  1. confere o GitHub CLI, a chave de assinatura e se a versao ainda nao existe;
  2. aumenta versionName e versionCode em app/build.gradle.kts;
  3. roda os testes (--skip-tests pula) e gera os APKs assinados (assembleRelease);
  4. copia os APKs para dist/ como UniversalMediaTools-<versao>-<abi>.apk, cada um com .sha256;
  5. faz commit de tudo e push (--no-git pula);
  6. publica no GitHub Releases. O app no celular ve a versao nova ao abrir
     (ou em Ajustes > Verificar atualizacoes do app), baixa, confere o SHA-256 e instala.

Se algo falhar antes do commit, a versao em app/build.gradle.kts volta ao que era.

Requer o GitHub CLI autenticado (gh auth login), o JDK 17 e keystore.properties
(sem ele o APK sai sem assinatura e o Android recusa instalar por cima).
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
DIST = ROOT / "dist"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
# x86_64 so serve para emulador: nao vai para o Releases
PUBLISHED_ABIS = ("arm64-v8a", "armeabi-v7a")

_NAME = re.compile(r'(versionName\s*=\s*")([^"]+)(")')
_CODE = re.compile(r"(versionCode\s*=\s*)(\d+)")
_REPO = re.compile(r'"UPDATE_REPO",\s*"\\"([^"\\]+)\\""')


def step(message: str) -> None:
    print(f"\n==> {message}", flush=True)


def fail(message: str) -> SystemExit:
    return SystemExit(f"[ERRO] {message}")


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=ROOT, **kwargs)


def find_gh() -> str:
    found = shutil.which("gh")
    if found:
        return found
    default = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "GitHub CLI" / "gh.exe"
    if default.is_file():
        return str(default)
    raise fail("GitHub CLI nao encontrado. Instale com: winget install GitHub.cli  (depois: gh auth login)")


def java_env() -> dict[str, str]:
    """Garante JAVA_HOME (o Gradle do Android precisa do JDK 17)."""
    env = dict(os.environ)
    if env.get("JAVA_HOME") and Path(env["JAVA_HOME"], "bin").is_dir():
        return env
    base = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Eclipse Adoptium"
    jdks = sorted(base.glob("jdk-17*")) if base.is_dir() else []
    if not jdks:
        raise fail("JDK 17 nao encontrado. Instale com: winget install EclipseAdoptium.Temurin.17.JDK")
    env["JAVA_HOME"] = str(jdks[-1])
    return env


def read_gradle() -> tuple[str, str, int, str]:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    name, code, repo = _NAME.search(text), _CODE.search(text), _REPO.search(text)
    if not (name and code and repo):
        raise fail(f"versionName/versionCode/UPDATE_REPO nao encontrados em {GRADLE_FILE}")
    return text, name.group(2), int(code.group(2)), repo.group(1)


def next_version(current: str, part: str) -> str:
    major, minor, patch = (int(n) for n in (current.split(".") + ["0", "0"])[:3])
    if part == "major":
        return f"{major + 1}.0.0"
    if part == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Publica uma versao nova do app Android.")
    parser.add_argument("notes", help="o que mudou nesta versao (aparece no app e no GitHub)")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--minor", action="store_true", help="recurso novo (0.2.0 -> 0.3.0)")
    group.add_argument("--major", action="store_true", help="mudanca grande (0.2.0 -> 1.0.0)")
    group.add_argument("--version", help="versao exata (ex.: 0.4.2)")
    parser.add_argument("--skip-tests", action="store_true", help="nao roda os testes")
    parser.add_argument("--no-git", action="store_true", help="nao faz commit nem push")
    args = parser.parse_args()

    step("Conferindo o ambiente")
    gh = find_gh()
    env = java_env()
    if not (ROOT / "keystore.properties").is_file():
        raise fail("keystore.properties nao encontrado (veja 'Assinatura' no README).")
    original, current, code, repo = read_gradle()
    part = "major" if args.major else "minor" if args.minor else "patch"
    version = args.version or next_version(current, part)
    tag = f"v{version}"
    if run([gh, "release", "view", tag, "--repo", repo], capture_output=True).returncode == 0:
        raise fail(f"A versao {tag} ja existe em {repo}.")
    print(f"  {current} (code {code}) -> {version} (code {code + 1})  em {repo}")

    step(f"Atualizando a versao para {version}")
    updated = _CODE.sub(rf"\g<1>{code + 1}", _NAME.sub(rf"\g<1>{version}\g<3>", original, 1), 1)
    GRADLE_FILE.write_text(updated, encoding="utf-8", newline="\n")

    try:
        gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        tasks = ["assembleRelease"] if args.skip_tests else ["testReleaseUnitTest", "assembleRelease"]
        step("Testes e build: " + " ".join(tasks))
        if run([gradlew, *tasks, "--console=plain", "-q"], env=env).returncode != 0:
            raise fail("O build ou os testes falharam (veja acima).")

        step("Preparando os APKs em dist/")
        if DIST.exists():
            shutil.rmtree(DIST)  # so a versao atual fica em disco; as antigas estao no GitHub
        DIST.mkdir()
        assets: list[Path] = []
        for abi in PUBLISHED_ABIS:
            src = APK_DIR / f"app-{abi}-release.apk"
            if not src.is_file():
                raise fail(f"{src.name} nao foi gerado.")
            apk = DIST / f"UniversalMediaTools-{version}-{abi}.apk"
            shutil.copy2(src, apk)
            digest = sha256(apk)
            checksum = apk.with_name(apk.name + ".sha256")
            checksum.write_text(f"{digest}  {apk.name}\n", encoding="ascii")
            assets += [apk, checksum]
            print(f"  {apk.name}  {apk.stat().st_size / 1e6:.1f} MB  sha256={digest[:16]}...")
    except BaseException:
        GRADLE_FILE.write_text(original, encoding="utf-8", newline="\n")
        print(f"  (versao revertida para {current})")
        raise

    if not args.no_git:
        step("Commit e push")
        run(["git", "add", "-A"], check=True)
        run(["git", "commit", "-q", "-m", f"Versao {version}\n\n{args.notes}"], check=True)
        if run(["git", "push", "-q"]).returncode != 0:
            raise fail("O push falhou. O commit foi feito; rode 'git push' e depois publique de novo.")

    step(f"Publicando {tag} no GitHub Releases")
    result = run([gh, "release", "create", tag, *map(str, assets), "--repo", repo,
                  "--title", f"Universal Media Tools para Android {version}",
                  "--notes", args.notes, "--latest"])
    if result.returncode != 0:
        raise fail("A publicacao falhou (veja a mensagem do gh acima).")
    print(f"\n[OK] Versao {version} publicada: https://github.com/{repo}/releases/tag/{tag}")
    print("     O app no celular mostra o aviso de atualizacao na proxima vez que abrir.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
