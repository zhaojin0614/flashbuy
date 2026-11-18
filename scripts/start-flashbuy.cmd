@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-flashbuy.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
    echo Start failed. Check the messages above and target\startup logs.
) else (
    echo Start finished.
)
pause
exit /b %EXIT_CODE%
