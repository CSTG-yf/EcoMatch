@echo off
setlocal
set "SCRIPT=%~dp0Start-BankSystem.ps1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
  echo Bank question system failed with exit code %RESULT%.
  pause
)
exit /b %RESULT%
