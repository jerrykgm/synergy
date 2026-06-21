# Synergy (Deskflow) - Build & Usage Guide

This repository contains the open-source codebase for **Synergy** (also known as the upstream **Deskflow** project), a keyboard and mouse sharing utility that allows you to seamlessly control multiple computers using a single mouse and keyboard.

By compiling the code directly from source, you can build and use ready-to-run binaries on your local machines completely for free without commercial serialization restrictions.

---

## 🛠️ Project Status & Current Build
This fork is pre-configured and optimized to build natively on **Windows 11 / Windows 10** using **Visual Studio 2022 (MSVC)** and **Qt 6.9.3**.
- **Version:** `1.20.4-dev`
- **Compiler Options:** Patched with exceptions handling rules (`/EHsc`) to ensure clean compilation under strict warnings.

---

## 🚀 How to Utilize (Pre-Compiled Build)

If you have already compiled the binaries on this machine, they are located inside the `build/bin/` folder.

### Quick Start (Automated Script)
To start Synergy automatically along with its required background system service, we've bundled a batch script inside the build folder:

1. Navigate to: `d:\Ithiya\synergy\build\bin\`
2. Right-click **`start_synergy.bat`** and select **Run as Administrator** (Admin rights are required to install the system daemon service).
3. The script will register the service, start it, and launch the Synergy configuration window.

### Manual Launch
If you prefer running manual commands (from an Administrator terminal):

1. **Install and start the background daemon:**
   ```powershell
   & "d:\Ithiya\synergy\build\bin\synergy-daemon.exe" --install
   Start-Service "Synergy"
   ```
2. **Launch the GUI:**
   ```powershell
   & "d:\Ithiya\synergy\build\bin\synergy.exe"
   ```

---

## 🔧 Building from Source

If you want to pull future updates or rebuild the project from scratch, follow these instructions.

### Prerequisites (Installed on this system)
1. **Visual Studio 2022 (MSVC)** with the *C++ Desktop Development* workload.
2. **CMake** (v3.22+) and **Ninja** (both bundled inside Visual Studio).
3. **vcpkg** (Installed at `D:\vcpkg`).
4. **Qt 6.9.3 MSVC 64-bit** (Installed at `C:\Qt\6.9.3\msvc2022_64` via `aqt`).

### Step-by-Step Compilation

1. **Install Python dependencies:**
   ```powershell
   python scripts/install_deps.py
   ```
2. **Configure CMake & Triplet Dependencies:**
   Open a terminal and set up the build system using vcpkg and the Qt installation path:
   ```powershell
   $env:VCPKG_ROOT = "D:\vcpkg"
   $env:QT_PATH = "C:\Qt\6.9.3\msvc2022_64"
   
   cmd.exe /c "call `"D:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat`" && `"D:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.EXE`" -B build --preset=windows-release -DCMAKE_MAKE_PROGRAM=`"D:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\Ninja\ninja.EXE`""
   ```
3. **Build the Release Executables:**
   ```powershell
   cmd.exe /c "call `"D:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat`" && `"D:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.EXE`" --build build -j8"
   ```

All compiled binaries will be outputted to [build/bin/](file:///d:/Ithiya/synergy/build/bin).

---

## 📄 License & Upstream Contribution
This software is licensed under the GPL-3.0 License. If you wish to contribute changes back to the main branch, please visit the upstream development page at [Deskflow Community Github](https://github.com/deskflow/deskflow).
