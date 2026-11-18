import os
import sys
import uuid
import time
import shutil
import json
import threading
import subprocess
import re
import hashlib
import glob
from urllib.parse import urljoin
from pathlib import Path
import requests
import minecraft_launcher_lib


def emit_status(message):
    print(f"STATUS|{message}", flush=True)


def directory_size(folder):
    total = 0
    try:
        for root, _, files in os.walk(folder):
            for name in files:
                try:
                    total += os.path.getsize(os.path.join(root, name))
                except OSError:
                    pass
    except OSError:
        pass
    return total


def human_size(value):
    value = max(0, float(value))
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}"
        value /= 1024


def human_time(seconds):
    if seconds is None or seconds < 0 or seconds > 7 * 24 * 60 * 60:
        return "—"
    seconds = int(seconds)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours}ч {minutes}м"
    if minutes:
        return f"{minutes}м {seconds}с"
    return f"{seconds}с"


def progress_callback(folder, started_size):
    state = {
        "started": time.monotonic(),
        "last_scan_at": time.monotonic(),
        "phase_started_size": started_size,
        "last_bytes": started_size,
        "downloaded": 0,
        "max": 0,
        "completed": 0,
        "speed": 0.0,
        "last_emit": 0.0,
    }
    lock = threading.Lock()
    stop_event = threading.Event()

    def emit_progress(force=False):
        now = time.monotonic()
        with lock:
            downloaded = state["downloaded"]
            maximum = state["max"]
            completed = state["completed"]
            speed = state["speed"]
            last_emit = state["last_emit"]
            if not force and now - last_emit < 0.45:
                return
            state["last_emit"] = now

        percent = min(100.0, max(0.0, completed / maximum * 100.0)) if maximum else 0.0
        estimated_total = downloaded * maximum / completed if completed > 0 and maximum else 0.0
        eta = (max(0.0, estimated_total - downloaded) / speed) if speed > 1 else None
        print(
            f"PROGRESS|{percent:.1f}|{human_size(downloaded)}|"
            f"{human_size(estimated_total)}|{human_size(speed)}|{human_time(eta)}",
            flush=True,
        )

    def monitor():
        while not stop_event.wait(0.8):
            now = time.monotonic()
            current = directory_size(folder)
            with lock:
                elapsed = max(0.05, now - state["last_scan_at"])
                delta = max(0, current - state["last_bytes"])
                instant_speed = delta / elapsed
                if state["speed"] <= 0:
                    state["speed"] = instant_speed
                else:
                    state["speed"] = state["speed"] * 0.55 + instant_speed * 0.45
                state["downloaded"] = max(0, current - state["phase_started_size"])
                state["last_bytes"] = current
                state["last_scan_at"] = now
            emit_progress()

    def set_max(value):
        current = directory_size(folder)
        with lock:
            state["max"] = max(0, int(value))
            state["completed"] = 0
            state["phase_started_size"] = current
            state["last_bytes"] = current
            state["last_scan_at"] = time.monotonic()
            state["downloaded"] = 0
            state["speed"] = 0.0
        emit_progress(True)

    def set_progress(value):
        with lock:
            state["completed"] = max(0, int(value))
        emit_progress(True)

    def set_status(value):
        emit_status(str(value))

    thread = threading.Thread(target=monitor, name="download-progress-monitor", daemon=True)
    thread.start()

    def stop(success=False):
        stop_event.set()
        thread.join(timeout=1.5)
        current = directory_size(folder)
        with lock:
            state["downloaded"] = max(state["downloaded"], max(0, current - state["phase_started_size"]))
            if success and state["max"]:
                state["completed"] = max(state["completed"], state["max"])
        emit_progress(True)

    return {"setStatus": set_status, "setProgress": set_progress, "setMax": set_max, "stop": stop}


def install_with_retries(install_function, attempts=3):
    for attempt in range(1, attempts + 1):
        try:
            return install_function()
        except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as error:
            if attempt >= attempts:
                raise
            emit_status(f"Проблема соединения ({attempt}/{attempts - 1}). Повтор через {attempt * 2} с...")
            time.sleep(attempt * 2)
        except requests.exceptions.RequestException:
            raise



def version_tuple(version):
    match = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?", version or "")
    return tuple(int(part or 0) for part in match.groups()) if match else (99, 0, 0)


def required_java_major(version):
    """Return the minimum Java major version required by the Minecraft line."""
    mc = version_tuple(version)
    if mc <= (1, 16, 5):
        return 8
    if mc == (1, 17, 0):
        return 16
    if mc < (1, 18, 0):
        return 16
    if mc < (1, 20, 5):
        return 17
    return 21


def java_major(java_executable):
    if not java_executable or not os.path.isfile(java_executable):
        return None
    try:
        probe = subprocess.run(
            [java_executable, "-version"],
            capture_output=True, text=True, timeout=6,
            encoding="utf-8", errors="replace"
        )
        output = (probe.stdout + "\n" + probe.stderr)
        match = re.search(r'version\s+"(?:1\.)?(\d+)', output, re.IGNORECASE)
        if not match:
            match = re.search(r'openjdk\s+(\d+)', output, re.IGNORECASE)
        return int(match.group(1)) if match else None
    except (OSError, subprocess.SubprocessError):
        return None


def find_java_for_version(version, configured=""):
    """Choose an installed Java matching Minecraft's runtime requirement."""
    required = required_java_major(version)
    candidates = []
    if configured:
        candidates.append(configured)

    for key in ("JAVA_HOME", "JDK_HOME", "JRE_HOME", "JAVA8_HOME", "JAVA17_HOME", "JAVA21_HOME"):
        home = os.environ.get(key)
        if home:
            candidates.extend((os.path.join(home, "bin", "javaw.exe"),
                               os.path.join(home, "bin", "java.exe"),
                               os.path.join(home, "bin", "java")))

    roots = [
        os.environ.get("ProgramFiles", r"C:\Program Files"),
        os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
        os.environ.get("LOCALAPPDATA", ""),
    ]
    patterns = (
        "Eclipse Adoptium\\jdk-*", "Eclipse Adoptium\\jre-*",
        "Java\\jdk*", "Java\\jre*", "Microsoft\\jdk-*",
        "Programs\\Eclipse Adoptium\\jdk-*", "Programs\\Eclipse Adoptium\\jre-*",
    )
    for root in roots:
        if not root:
            continue
        for pattern in patterns:
            for home in glob.glob(os.path.join(root, pattern)):
                candidates.extend((os.path.join(home, "bin", "javaw.exe"),
                                   os.path.join(home, "bin", "java.exe"),
                                   os.path.join(home, "bin", "java")))

    for executable in ("javaw.exe", "java.exe", "java"):
        found = shutil.which(executable)
        if found:
            candidates.append(found)

    seen = set()
    for candidate in candidates:
        candidate = os.path.abspath(os.path.expandvars(candidate))
        normalized = os.path.normcase(candidate)
        if normalized in seen:
            continue
        seen.add(normalized)
        actual = java_major(candidate)
        if actual is not None and actual >= required:
            return candidate
    return ""


def get_optifine_release(mc_version):
    """Find the latest non-preview OptiFine build on the official downloads page."""
    response = requests.get(
        "https://optifine.net/downloads",
        headers={"User-Agent": "JustStudioLauncher/2.8", "Accept-Language": "en-US,en;q=0.9"},
        timeout=20,
    )
    response.raise_for_status()
    html = response.text
    heading = re.search(
        rf"Minecraft\s+{re.escape(mc_version)}\b", html, re.IGNORECASE
    )
    if not heading:
        return None
    next_heading = re.search(r"Minecraft\s+\d+\.\d+(?:\.\d+)?", html[heading.end():], re.IGNORECASE)
    section = html[heading.start():heading.end() + next_heading.start()] if next_heading else html[heading.start():]
    filenames = re.findall(r"OptiFine_[A-Za-z0-9._-]+?\.jar", section, re.IGNORECASE)
    filename = next((name for name in filenames if not re.search(r"(?i)(pre|preview|alpha|beta|rc)", name)), None)
    if not filename:
        return None
    row_start = section.lower().find(filename.lower())
    table_row_start = section.lower().rfind("<tr", 0, row_start)
    table_row_end = section.lower().find("</tr>", row_start)
    if table_row_start >= 0 and table_row_end >= 0:
        row = section[table_row_start:table_row_end + 5]
    else:
        row = section[max(0, row_start - 900):row_start + 900]
    row_text = re.sub(r"<[^>]+>", " ", row)
    forge_match = re.search(r"Forge\s+(?:#)?([0-9]+(?:\.[0-9]+){1,3})", row_text, re.IGNORECASE)
    forge_min = forge_match.group(1) if forge_match else ""
    return {"filename": filename, "forge_min": forge_min}


def download_optifine(mc_version, mc_dir, callback=None):
    release = get_optifine_release(mc_version)
    if not release:
        raise RuntimeError(f"Для Minecraft {mc_version} на официальном сайте не найдена стабильная версия OptiFine.")
    filename = release["filename"]
    target_dir = os.path.join(mc_dir, "mods")
    os.makedirs(target_dir, exist_ok=True)
    for old_name in os.listdir(target_dir):
        if old_name.lower().startswith("optifine_") and old_name.lower().endswith(".jar") and old_name != filename:
            try:
                os.remove(os.path.join(target_dir, old_name))
            except OSError:
                pass
    target = os.path.join(target_dir, filename)
    if os.path.isfile(target) and os.path.getsize(target) > 100_000:
        emit_status(f"OptiFine уже установлен: {filename}")
        return target

    emit_status(f"Загрузка OptiFine {filename} с официального сайта...")
    session = requests.Session()
    headers = {"User-Agent": "Mozilla/5.0 JustStudioLauncher/2.8", "Referer": "https://optifine.net/downloads"}
    first = session.get("https://optifine.net/adloadx", params={"f": filename}, headers=headers, timeout=30)
    first.raise_for_status()
    data = first.content
    if not data.startswith(b"PK"):
        page = first.text
        match = re.search(r"href=[\"']([^\"']*downloadx\?[^\"']+)[\"']", page, re.IGNORECASE)
        if not match:
            match = re.search(r"(downloadx\?f=[^\"'<> ]+)", page, re.IGNORECASE)
        if not match:
            raise RuntimeError("Официальный сайт OptiFine не выдал прямую ссылку на файл (возможно, временная проверка сайта).")
        from urllib.parse import urljoin
        download_url = urljoin(first.url, match.group(1).replace("&amp;", "&"))
        downloaded = session.get(download_url, headers={**headers, "Referer": first.url}, timeout=60)
        downloaded.raise_for_status()
        data = downloaded.content
    import io, zipfile
    if not zipfile.is_zipfile(io.BytesIO(data)):
        raise RuntimeError("Сайт OptiFine вернул не JAR-файл. Файл не установлен, чтобы не повредить сборку.")
    temp = target + ".part"
    with open(temp, "wb") as stream:
        stream.write(data)
    os.replace(temp, target)
    emit_status(f"OptiFine установлен в папку mods: {filename}")
    return target


def _version_profile_chain(version, mc_dir):
    chain = []
    current = version
    seen = set()
    while current and current not in seen:
        seen.add(current)
        profile_path = os.path.join(mc_dir, "versions", current, current + ".json")
        if not os.path.isfile(profile_path):
            break
        try:
            with open(profile_path, "r", encoding="utf-8") as stream:
                profile = json.load(stream)
        except (OSError, ValueError):
            break
        chain.append((current, profile))
        current = profile.get("inheritsFrom", "")
    return chain


def repair_corrupt_libraries(version, mc_dir):
    damaged = []
    for _profile_id, profile in _version_profile_chain(version, mc_dir):
        for library in profile.get("libraries", []):
            artifact = (library.get("downloads") or {}).get("artifact") or {}
            relative = artifact.get("path")
            expected = artifact.get("sha1")
            if not relative or not expected:
                continue
            target = os.path.join(mc_dir, "libraries", *relative.replace("\\", "/").split("/"))
            if not os.path.isfile(target):
                continue
            try:
                digest = hashlib.sha1()
                with open(target, "rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                if digest.hexdigest().lower() != str(expected).lower():
                    damaged.append(target)
            except OSError:
                damaged.append(target)
    for target in damaged:
        try:
            os.remove(target)
            emit_status("Удалена повреждённая библиотека: " + os.path.basename(target))
        except OSError as error:
            raise RuntimeError(f"Не удалось удалить повреждённую библиотеку {target}: {error}")
    if damaged:
        chain = _version_profile_chain(version, mc_dir)
        base_id = chain[-1][0] if chain else version
        emit_status(f"Восстанавливаю повреждённые библиотеки для Minecraft {base_id}...")
        install_with_retries(lambda: minecraft_launcher_lib.install.install_minecraft_version(base_id, mc_dir))
        remaining = []
        for _profile_id, profile in _version_profile_chain(version, mc_dir):
            for library in profile.get("libraries", []):
                artifact = (library.get("downloads") or {}).get("artifact") or {}
                relative, expected = artifact.get("path"), artifact.get("sha1")
                if not relative or not expected:
                    continue
                target = os.path.join(mc_dir, "libraries", *relative.replace("\\", "/").split("/"))
                if os.path.isfile(target):
                    digest = hashlib.sha1()
                    with open(target, "rb") as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                            digest.update(chunk)
                    if digest.hexdigest().lower() != str(expected).lower():
                        remaining.append(target)
        if remaining:
            raise RuntimeError("Не удалось восстановить контрольную сумму библиотек: " + ", ".join(os.path.basename(x) for x in remaining[:3]))



VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VERSION_CACHE_TTL = 6 * 60 * 60


def get_release_catalog(mc_dir):
    cache_dir = os.path.join(mc_dir, ".juststudio-launcher-cache")
    cache_file = os.path.join(cache_dir, "version_manifest_v2.json")
    cached = None
    cached_at = 0
    try:
        with open(cache_file, "r", encoding="utf-8") as stream:
            envelope = json.load(stream)
        cached = envelope.get("manifest")
        cached_at = float(envelope.get("saved_at", 0))
        if not isinstance(cached, dict) or not isinstance(cached.get("versions"), list):
            cached = None
    except (OSError, ValueError, TypeError):
        cached = None

    if cached and time.time() - cached_at < VERSION_CACHE_TTL:
        emit_status("Список версий загружен из кэша.")
        return cached.get("versions", [])

    last_error = None
    for attempt in range(2):
        try:
            response = requests.get(
                VERSION_MANIFEST_URL,
                headers={"User-Agent": "JustStudioLauncher/2.8"},
                timeout=(4, 8),
            )
            response.raise_for_status()
            manifest = response.json()
            if not isinstance(manifest, dict) or not isinstance(manifest.get("versions"), list):
                raise RuntimeError("Сервер Mojang вернул некорректный каталог версий.")
            os.makedirs(cache_dir, exist_ok=True)
            temporary = cache_file + ".part"
            with open(temporary, "w", encoding="utf-8") as stream:
                json.dump({"saved_at": time.time(), "manifest": manifest}, stream, ensure_ascii=False)
            os.replace(temporary, cache_file)
            emit_status("Официальный каталог версий Mojang обновлён.")
            return manifest["versions"]
        except (requests.RequestException, ValueError, RuntimeError) as error:
            last_error = error
            if attempt == 0:
                time.sleep(0.7)

    if cached:
        emit_status(f"Не удалось обновить каталог Mojang ({last_error}). Используется сохранённый кэш.")
        return cached.get("versions", [])
    raise RuntimeError(f"Не удалось получить каталог версий Minecraft: {last_error}")


def loader_versions(loader_name, minecraft_version):
    try:
        loader_api = minecraft_launcher_lib.mod_loader.get_mod_loader(loader_name)
        builds = loader_api.get_loader_versions(minecraft_version, True)
        normalized = []
        for build in builds or []:
            if isinstance(build, str):
                normalized.append(build)
            elif isinstance(build, dict):
                identifier = build.get("version") or build.get("id")
                stable = build.get("stable")
                if identifier and stable is True:
                    normalized.append(str(identifier))
        return normalized
    except (TypeError, AttributeError, KeyError, ValueError):
        emit_status(f"Установленная minecraft-launcher-lib не умеет надёжно получать стабильные версии {loader_name}. Обновите библиотеку.")
        return []

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 3:
        emit_status("Недостаточно аргументов")
        return 2

    action = sys.argv[1]
    mc_dir = os.path.abspath(sys.argv[-1])
    os.makedirs(mc_dir, exist_ok=True)

    if action == "list":
        for item in minecraft_launcher_lib.utils.get_installed_versions(mc_dir):
            version_id = item.get("id")
            if version_id:
                print(f"VERSION|{version_id}", flush=True)
        return 0

    if action == "catalog":
        versions = get_release_catalog(mc_dir)
        for item in versions:
            if item.get("type") == "release" and item.get("id"):
                print(f"VERSION|{item['id']}", flush=True)
        return 0

    if action == "components":
        version = sys.argv[2] if len(sys.argv) >= 4 else ""
        latest_forge_build = ""
        for loader_name in ("forge", "fabric", "neoforge", "quilt"):
            try:
                builds = loader_versions(loader_name, version)
                available = bool(builds)
                if loader_name == "forge" and builds:
                    latest_forge_build = str(builds[0])
            except Exception as error:
                emit_status(f"Не удалось получить метаданные {loader_name}: {error}")
                available = False
            print(f"COMPONENT|{loader_name}|{'true' if available else 'false'}", flush=True)
        try:
            optifine = get_optifine_release(version)
            available = bool(optifine and optifine.get("forge_min") and latest_forge_build)
            if available:
                min_parts = tuple(int(part) for part in re.findall(r"\d+", optifine["forge_min"]))
                forge_parts = tuple(int(part) for part in re.findall(r"\d+", latest_forge_build))
                available = forge_parts >= min_parts
            print(f"COMPONENT|optifine|{'true' if available else 'false'}", flush=True)
            if optifine:
                print(f"OPTIFINE|{optifine['filename']}|{optifine.get('forge_min', '')}", flush=True)
        except Exception as error:
            emit_status(f"Не удалось проверить OptiFine: {error}")
            print("COMPONENT|optifine|false", flush=True)
        return 0

    if action in ("install", "reinstall"):
        version = sys.argv[2]
        loader_spec = sys.argv[3] if len(sys.argv) >= 5 else "vanilla"
        selected_parts = {part.strip().lower() for part in loader_spec.split("+") if part.strip()}
        optifine_selected = "optifine" in selected_parts
        loader = next((part for part in ("forge", "fabric", "neoforge", "quilt") if part in selected_parts), "vanilla")
        if optifine_selected and loader != "forge":
            raise RuntimeError("OptiFine в этой версии лаунчера устанавливается вместе с Forge. Выберите Forge + OptiFine.")
        install_version = version
        if action == "reinstall":
            profile_json = os.path.join(mc_dir, "versions", version, version + ".json")
            if os.path.isfile(profile_json):
                try:
                    with open(profile_json, "r", encoding="utf-8") as stream:
                        profile = json.load(stream)
                    install_version = profile.get("inheritsFrom", version)
                    lowered = version.lower()
                    if "neoforge" in lowered:
                        loader = "neoforge"
                    elif "forge" in lowered:
                        loader = "forge"
                    elif "fabric" in lowered:
                        loader = "fabric"
                except (OSError, ValueError):
                    install_version = version
            for damaged in {version, install_version}:
                version_folder = os.path.join(mc_dir, "versions", damaged)
                if os.path.isdir(version_folder):
                    emit_status(f"Удаляю повреждённые файлы версии {damaged}...")
                    shutil.rmtree(version_folder, ignore_errors=True)
        emit_status(f"Подготовка Minecraft {install_version}...")
        before = directory_size(mc_dir)
        callback = progress_callback(mc_dir, before)
        installation_succeeded = False
        try:
            install_with_retries(
                lambda: minecraft_launcher_lib.install.install_minecraft_version(
                    install_version, mc_dir, callback=callback
                )
            )

            if loader == "optifine":
                emit_status("Автоматическая установка OptiFine пока не поддерживается движком. Базовая версия Minecraft установлена.")
                return 3
            if loader != "vanilla":
                emit_status(f"Установка загрузчика {loader}...")
                mod_loader = minecraft_launcher_lib.mod_loader.get_mod_loader(loader)
                stable_versions = loader_versions(loader, install_version)
                if not stable_versions:
                    raise RuntimeError(
                        f"Для Minecraft {install_version} не найдена стабильная версия {loader}. "
                        "Предварительные сборки автоматически не устанавливаются."
                    )
                stable_loader_version = stable_versions[0]
                emit_status(f"Выбрана стабильная версия {loader}: {stable_loader_version}")
                installer_java = find_java_for_version(install_version)
                installed_loader_version = install_with_retries(
                    lambda: mod_loader.install(install_version, mc_dir, loader_version=stable_loader_version, callback=callback, java=installer_java or None)
                )
                print(f"SELECTED|{installed_loader_version}", flush=True)
                emit_status(f"Установлен профиль {installed_loader_version}")

            if optifine_selected:
                release = get_optifine_release(install_version)
                if not release or not release.get("forge_min"):
                    raise RuntimeError(f"Для Minecraft {install_version} нет стабильной Forge-совместимой сборки OptiFine.")
                if loader == "forge":
                    installed_forge = installed_loader_version if 'installed_loader_version' in locals() else ""
                    forge_actual = re.search(r"forge[- ]([0-9]+(?:\.[0-9]+){1,3})", installed_forge, re.IGNORECASE)
                    if forge_actual and tuple(map(int, forge_actual.group(1).split("."))) < tuple(map(int, release["forge_min"].split("."))):
                        raise RuntimeError(f"OptiFine требует Forge {release['forge_min']} или новее, а установлена сборка {forge_actual.group(1)}.")
                download_optifine(install_version, mc_dir, callback)

            installation_succeeded = True
            emit_status(f"Установка Minecraft {version} завершена.")
            return 0
        finally:
            callback["stop"](installation_succeeded)

    if action == "launch":
        nickname = sys.argv[2]
        version = sys.argv[3]
        ram = max(1, min(32, int(sys.argv[4])))
        java_path = sys.argv[5] if len(sys.argv) >= 7 else ""
        auto_java = find_java_for_version(version, java_path)
        required_java = required_java_major(version)
        if auto_java:
            emit_status(f"Для Minecraft {version} выбрана Java {java_major(auto_java)} (требуется Java {required_java}+).")
        else:
            emit_status(
                f"Не найдена Java {required_java} или новее для Minecraft {version}. "
                "Проверьте Java в настройках и установите подходящий runtime."
            )
        if not auto_java:
            raise RuntimeError(
                f"Не найдена совместимая Java для Minecraft {version}. "
                f"Требуется Java {required_java} или новее. Укажите java.exe в настройках."
            )
        installed = [v.get("id") for v in minecraft_launcher_lib.utils.get_installed_versions(mc_dir)]
        if version not in installed:
            emit_status(f"Версия {version} не установлена. Сначала установите её.")
            return 2
        profile_chain = _version_profile_chain(version, mc_dir)
        base_version = profile_chain[-1][0] if profile_chain else version
        emit_status(f"Проверка файлов Minecraft {base_version} перед запуском...")
        install_with_retries(
            lambda: minecraft_launcher_lib.install.install_minecraft_version(base_version, mc_dir)
        )
        repair_corrupt_libraries(version, mc_dir)
        
        # === ИСПРАВЛЕННЫЙ БЛОК ОПЦИЙ ЗАПУСКА ===
        options = {
            "username": nickname,
            "uuid": uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + nickname).hex,
            "token": "00000000000000000000000000000000",
            "launcherName": "JustStudioLauncher",
            "launcherVersion": "2.8",
            "gameDirectory": mc_dir,
            "jvmArguments": [
                f"-Xms{min(2, ram)}G", 
                f"-Xmx{ram}G",
                "-Dminecraft.api.auth.host=https://0.0.0.0",
                "-Dminecraft.api.account.host=https://0.0.0.0",
                "-Dminecraft.api.session.host=https://0.0.0.0",
                "-Dminecraft.api.services.host=https://0.0.0.0",
            ],
        }
        command = minecraft_launcher_lib.command.get_minecraft_command(version, mc_dir, options)
        
        # Замена msa на legacy для оффлайн-профилей
        if not options["token"] or options["token"] == "00000000000000000000000000000000":
            for index, argument in enumerate(command[:-1]):
                if argument == "--userType" and command[index + 1] == "msa":
                    command[index + 1] = "legacy"

        # Удаление ключей Xbox Live для разблокировки мультиплеера на 1.16.4+
        cleaned_command = []
        skip_next = False
        for i, arg in enumerate(command):
            if skip_next:
                skip_next = False
                continue
            if arg in ("--xuid", "--clientId"):
                skip_next = True
                continue
            cleaned_command.append(arg)
        command = cleaned_command

        if auto_java and os.path.isfile(auto_java):
            command[0] = auto_java
        elif java_path:
            raise RuntimeError(
                f"Выбранная Java несовместима с Minecraft {version}. "
                f"Требуется Java {required_java} или новее."
            )
        emit_status(f"Запускаю Minecraft {version} для {nickname}...")
        emit_status("Режим offline. Закройте игру обычным способом.")
        game = subprocess.Popen(command, cwd=mc_dir, shell=False)
        return game.wait()

    emit_status("Неизвестное действие")
    return 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        emit_status(f"Ошибка движка: {error}")
        import traceback
        traceback.print_exc()
        raise SystemExit(1)