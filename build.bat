@echo off
echo Stopping any running Synergy instances and services...
net stop Synergy 2>nul
taskkill /f /im synergy-client.exe /im synergy-daemon.exe /im synergy.exe /im deskflow-client.exe /im deskflow-daemon.exe /im deskflow.exe 2>nul

call "D:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
echo Configuring CMake...
"D:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" -B build --preset=windows-release
echo Building...
"D:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" --build build -j8
