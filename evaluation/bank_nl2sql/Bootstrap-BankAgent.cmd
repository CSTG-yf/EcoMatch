@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..\..") do set "REPO_ROOT=%%~fI"
set "PYTHON_EXE=%REPO_ROOT%\evaluation\.venv\Scripts\python.exe"
set "BOOTSTRAP=%REPO_ROOT%\evaluation\bank_nl2sql\bootstrap_bank_agent.py"
set "RECEIPT_DIR=%REPO_ROOT%\.local-dev\bank-nl2sql\official-v3"
set "RECEIPT=%RECEIPT_DIR%\bootstrap-receipt.json"

if not exist "%PYTHON_EXE%" (
  echo Project evaluation virtual environment was not found:
  echo   %PYTHON_EXE%
  echo Create evaluation\.venv and install evaluation requirements first.
  pause
  exit /b 2
)

if not defined ECOMATCH_ADMIN_PASSWORD set "ECOMATCH_ADMIN_PASSWORD=123456"
if not exist "%RECEIPT_DIR%" mkdir "%RECEIPT_DIR%"

"%PYTHON_EXE%" "%BOOTSTRAP%" "%REPO_ROOT%\evaluation\bank_nl2sql" --output "%RECEIPT%" %*
set "RESULT=%ERRORLEVEL%"
set "ECOMATCH_ADMIN_PASSWORD="
set "ECOMATCH_AUTH_TOKEN="

if not "%RESULT%"=="0" (
  echo Bank Agent bootstrap failed with exit code %RESULT%.
) else (
  echo Bank Agent bootstrap completed.
  echo Bootstrap receipt: %RECEIPT%
)
pause
exit /b %RESULT%
