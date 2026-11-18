@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-flashbuy.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
    echo Stop failed. Check the messages above.
) else (
    echo Stop finished.
)
pause
exit /b %EXIT_CODE%
