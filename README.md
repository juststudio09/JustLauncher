# 💜 Just Studio Launcher 1.0

**A modern Minecraft launcher by Just Studio.**

Just Studio Launcher — удобный лаунчер Minecraft с современным интерфейсом в неоновом стиле, управлением игровыми клиентами и настройками запуска.

<p align="center">
  <b>Version 1.0.0 · Release · Windows</b>
</p>

---

## ✨ Возможности

- 🎮 **Minecraft Versions** — просмотр, выбор и установка версий Minecraft.
- 👥 **Multiple Clients** — создание и управление отдельными игровыми клиентами.
- 🧩 **Mod Loaders** — установка Forge, Fabric, NeoForge и Quilt.
- 📁 **Game Directory** — удобный доступ к папке игры.
- 🧱 **Mods & Resource Packs** — быстрый доступ к модам и ресурспакам.
- ⚙️ **Custom Settings** — настройка игрового ника, количества RAM и Java.
- ☕ **Java Detection** — поиск Java и проверка совместимости с версией Minecraft.
- 📊 **Download Progress** — отображение прогресса загрузки.
- 🖥️ **Built-in Console** — просмотр сообщений и ошибок игрового движка.
- 🎨 **Modern UI** — тёмный интерфейс с фиолетовыми неоновыми элементами.
- 💾 **Local Profiles** — сохранение настроек и игровых профилей.
- 🔔 **System Tray** — поддержка системного трея и восстановление окна лаунчера.

## 📥 Download

**Just Studio Launcher 1.0 — Release**

[Download the latest release](https://github.com/juststudio09/JustLauncher/releases/latest)

## 🖥️ System Requirements

- OS: Windows 10 / Windows 11
- Java: 17 or newer (for the launcher)
- Python: 3.10 or newer (for the Minecraft engine)
- Internet connection

## 🚀 Running from source

Clone the repository:

```bash
git clone https://github.com/juststudio09/JustLauncher.git
cd JustLauncher
```

Install Python dependencies:

```bash
py -3 -m pip install -r engine/requirements.txt
```

Run the launcher:

```bash
mvn clean javafx:run
```

## 🛠️ Technologies

- Java 17
- JavaFX
- Python
- minecraft-launcher-lib
- Maven

## ⚠️ Notes

- Microsoft account authentication is not yet implemented.
- OptiFine installation is not included as a verified feature.
- A compatible Java runtime is required to launch Minecraft.
-