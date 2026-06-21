@echo off
:: Check for administrative privileges
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ======================================================
    echo ERROR: This script must be run as Administrator!
    echo Please right-click this file and select "Run as administrator".
    echo ======================================================
    pause
    exit /b
)

echo Generating IP Connection QR Code...
python "%~dp0show_ip_qr.py"

echo Registering Synergy Daemon Service...
"%~dp0synergy-daemon.exe" --install

echo Starting Synergy Daemon Service...
net start Synergy

echo Starting Synergy GUI...
start "" "%~dp0synergy.exe"

echo Done!
pause
