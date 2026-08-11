@echo off
rem ============================================================================
rem  Bank NL2SQL official database import package (v2.0.2) - double-click
rem  wrapper for Import-OfficialBankData.ps1 (companion import package, NOT a
rem  runtime semantic.mv.db).
rem
rem  One-click default: imports into <repo>\.local-dev\state\semantic after
rem  verifying the manifest and artifact hashes; refuses a locked target;
rem  touches only the bank_* benchmark tables/views.
rem
rem  Custom target / toolchain:
rem    Import-OfficialBankData.cmd -TargetDatabase C:\tmp\semantic
rem    Import-OfficialBankData.cmd -TargetDatabase C:\tmp\semantic ^
rem        -JavaPath C:\jdk\bin\java.exe -H2JarPath C:\h2\h2-2.2.224.jar
rem ============================================================================
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Import-OfficialBankData.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
    echo Import failed with exit code %EXIT_CODE%. See messages above.
) else (
    echo Import completed successfully.
)
pause
exit /b %EXIT_CODE%
