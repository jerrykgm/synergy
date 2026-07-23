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

:: Query current mode from Registry configuration
for /f "tokens=3" %%a in ('reg query HKCU\Software\Synergy\Synergy /v groupServerChecked 2^>nul') do set "SERVER_CHECKED=%%a"

if "%SERVER_CHECKED%"=="true" (
    echo ======================================================
    echo  SWAPPING MODE: Server -> Client
    echo ======================================================
    
    :: Stop daemon
    echo Stopping Synergy Daemon...
    net stop Synergy >nul 2>&1
    
    :: Modify Registry settings to match Client configuration
    echo Updating configuration to Client Mode...
    reg add HKCU\Software\Synergy\Synergy /v groupServerChecked /t REG_SZ /d "false" /f >nul
    reg add HKCU\Software\Synergy\Synergy /v groupClientChecked /t REG_SZ /d "true" /f >nul
    
    :: Fetch last connected Server IP from settings (fallback to manual prompt if empty)
    for /f "tokens=3" %%a in ('reg query HKCU\Software\Synergy\Synergy /v serverHostname 2^>nul') do set "SAVED_IP=%%a"
    
    if "%SAVED_IP%"=="" (
        set /p TARGET_IP="Enter the Server IP to connect to: "
        reg add HKCU\Software\Synergy\Synergy /v serverHostname /t REG_SZ /d "%TARGET_IP%" /f >nul
    ) else (
        echo Connecting to last saved Server IP: %SAVED_IP%
        set "TARGET_IP=%SAVED_IP%"
    )
    
    :: Run Client process in foreground
    echo Starting Synergy Client...
    start "" "%~dp0synergy-client.exe" -f --no-tray --server %TARGET_IP%

) else (
    echo ======================================================
    echo  SWAPPING MODE: Client -> Server
    echo ======================================================
    
    :: Terminate active client processes
    echo Stopping Synergy Client...
    taskkill /f /im synergy-client.exe >nul 2>&1
    
    :: Modify Registry settings to match Server configuration
    echo Updating configuration to Server Mode...
    reg add HKCU\Software\Synergy\Synergy /v groupServerChecked /t REG_SZ /d "true" /f >nul
    reg add HKCU\Software\Synergy\Synergy /v groupClientChecked /t REG_SZ /d "false" /f >nul
    
    :: Generate connection QR code
    python "%~dp0show_ip_qr.py"
    
    :: Start daemon and server
    echo Starting Synergy Daemon...
    net start Synergy >nul 2>&1
    
    echo Starting Synergy GUI Server...
    start "" "%~dp0synergy.exe"
)

echo Done!
pause
