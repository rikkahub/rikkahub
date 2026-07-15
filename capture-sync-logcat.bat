@echo off
setlocal EnableExtensions

cd /d "%~dp0"

where adb.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] adb.exe not found. Install Android platform-tools and add to PATH.
    exit /b 1
)

set "OUTDIR=%~dp0logs"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%I"
set "OUTFILE=%OUTDIR%\sync-logcat-%TS%.txt"

echo [INFO] Clearing logcat buffer...
adb logcat -c

echo [INFO] Capturing Perry/cloud sync logs.
echo [INFO] Output: %OUTFILE%
echo [INFO] Reproduce the issue on the phone, then press Ctrl+C to stop.
echo.

adb logcat -v threadtime ^
  CloudSyncRepository:V ^
  CloudSyncWorker:V ^
  SettingsDomainSync:V ^
  FileTransferWorker:V ^
  FileDomainSync:V ^
  MessageNodeDomainSync:V ^
  ChatService:V ^
  RikkaHubBackupImporter:V ^
  ConversationDomainSync:V ^
  UploadProgressTracker:V ^
  *:S > "%OUTFILE%"

echo.
echo [DONE] Saved: %OUTFILE%
exit /b 0
