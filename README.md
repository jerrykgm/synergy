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

## 🍏 Utilizing on macOS (Mac)

To connect your macOS computer with your Windows PC, you can compile Synergy on your Mac or run an existing build.

### Building on macOS
1. **Install dependencies** (requires Homebrew):
   ```bash
   brew install cmake ninja qt@6 openssl
   ```
2. **Configure and Build:**
   ```bash
   cmake -B build --preset=macos-release -DQT_PATH="$(brew --prefix qt@6)"
   cmake --build build -j$(sysctl -n hw.ncpu)
   ```
3. The executable output will be located in `build/bin/Deskflow.app`.

---

## 🤖 Utilizing on Android

Deskflow/Synergy supports Android as a client (allowing you to control your Android device using your PC's keyboard and mouse). 

### Setup via Deskflow Android App
1. Compile or download the Deskflow Android client app (`.apk`).
2. **Configure SDK Build Environment (Optional):**
   To compile the APK yourself, you will need the Android SDK & NDK installed:
   ```bash
   # Configure Android toolchain path
   cmake -B build-android -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake
   cmake --build build-android
   ```
3. Install the generated `.apk` on your Android device.
4. Open the app on Android, type in your Windows PC's local IP address, and press **Connect**.

### 💻 System Compatibility & Testing
This client has been optimized and verified to work across the following environments:
- **Android Client Devices:**
  - **Xiaomi Mi Pad 5** (MIUI Tablet)
  - **Samsung Galaxy Tab A9+** (One UI Tablet)
    - *Note for Samsung Users:* Samsung's default keyboard may ignore accessibility commands to hide. For the best experience (where the onscreen keyboard stays perfectly hidden while typing from a PC), we highly recommend installing and setting **Gboard (Google Keyboard)** as your default keyboard.
- **Host / Server Operating Systems:**
  - **macOS**
  - **Windows 11**
  - **Windows 10**

---

## 🔍 Troubleshooting Commands & Common Fixes

### 1. Fix "Both toggles on at the same time" (Dual Server/Client state)
If Synergy opens with both *"Use this computer's keyboard and mouse"* and *"Use another computer's keyboard and mouse"* checked simultaneously, it is caused by stale Windows Registry values.

**Fix via Command Prompt:**
```cmd
reg add "HKCU\Software\Synergy\Synergy" /v groupServerChecked /t REG_SZ /d "false" /f
```
Or fix in PowerShell:
```powershell
Set-ItemProperty -Path "HKCU:\Software\Synergy\Synergy" -Name "groupServerChecked" -Value "false"
```

---

### 2. Fix "cannot listen for clients: cannot bind address: The specified address is already in use" (Error Code 62097)
This error occurs when an existing instance of `synergy-server.exe` is already running in the background and listening on port `24800`.

**Check process using port 24800:**
```powershell
Get-NetTCPConnection -LocalPort 24800 | Select-Object LocalAddress, LocalPort, OwningProcess
```

**Force kill all background Synergy processes:**
```powershell
Stop-Service Synergy -ErrorAction SilentlyContinue
Get-Process synergy*, deskflow* -ErrorAction SilentlyContinue | Stop-Process -Force
```

---

### 3. Fix "Failed to connect to server: server refused client with our name"
This happens when the Server PC does not have the exact screen name of the Client added to its layout.

**Solution:**
1. On the **Server PC**, click **Configure Server...**.
2. Drag a new screen icon onto the grid.
3. Double-click the screen icon and set its **Screen name** to match the **Client PC's exact computer name** (e.g. `Lenevo` or `MacBook-Pro.local`).
4. Click **OK** $\rightarrow$ **OK** $\rightarrow$ **Apply**.

---

### 4. Fix "Please check your TLS and firewall settings" / Connection Timeouts
If the Client fails to connect or times out:

**A. Disable TLS Encryption (if not needed on local network):**
1. On both computers, open Synergy $\rightarrow$ **Edit** $\rightarrow$ **Settings**.
2. Uncheck **Enable TLS Encryption** on both machines.
3. Click **OK** and restart Synergy.

**B. Open Windows Firewall Port 24800 (Server PC):**
Run in PowerShell (Administrator):
```powershell
New-NetFirewallRule -DisplayName "Synergy Port 24800 Inbound" -Direction Inbound -Protocol TCP -LocalPort 24800 -Action Allow
New-NetFirewallRule -DisplayName "Synergy Port 24800 Outbound" -Direction Outbound -Protocol TCP -LocalPort 24800 -Action Allow
```

---

## 📄 License & Upstream Contribution
This software is licensed under the GPL-3.0 License. If you wish to contribute changes back to the main branch, please visit the upstream development page at [Deskflow Community Github](https://github.com/deskflow/deskflow).


