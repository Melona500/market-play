@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-market-play.ps1"
set "LAUNCH_EXIT_CODE=%errorlevel%"
if not "%LAUNCH_EXIT_CODE%"=="0" (
    echo.
    echo Server launcher failed with exit code %LAUNCH_EXIT_CODE%. Review the message above.
    pause
)
exit /b %LAUNCH_EXIT_CODE%
