@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-flashbuy.ps1" -SkipBackend -SkipBuild %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
    echo Dependency startup failed. Check the messages above and target\startup logs.
) else (
    echo Dependencies are ready. Start FlashbuyApplication in IDEA.
)
pause
exit /b %EXIT_CODE%
