@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..\..") do set "REPO_ROOT=%%~fI"
set "PYTHON_EXE=%REPO_ROOT%\evaluation\.venv\Scripts\python.exe"
set "BOOTSTRAP=%REPO_ROOT%\evaluation\bank_nl2sql\bootstrap_bank_agent.py"

if not exist "%PYTHON_EXE%" (
  echo Project evaluation virtual environment was not found:
  echo   %PYTHON_EXE%
  echo Create evaluation\.venv and install evaluation requirements first.
  pause
  exit /b 2
)

set /p "MODEL_ID=Bank semantic model ID [1]: "
if not defined MODEL_ID set "MODEL_ID=1"
set /p "CHAT_MODEL_ID=Chat model ID [1]: "
if not defined CHAT_MODEL_ID set "CHAT_MODEL_ID=1"
set /p "ECOMATCH_AUTH_TOKEN=Administrator token: "
if not defined ECOMATCH_AUTH_TOKEN (
  echo Administrator token is required.
  pause
  exit /b 2
)

"%PYTHON_EXE%" "%BOOTSTRAP%" "%REPO_ROOT%\evaluation\bank_nl2sql" --model-id "%MODEL_ID%" --chat-model-id "%CHAT_MODEL_ID%"
set "RESULT=%ERRORLEVEL%"
set "ECOMATCH_AUTH_TOKEN="

if not "%RESULT%"=="0" (
  echo Bank Agent bootstrap failed with exit code %RESULT%.
) else (
  echo Bank Agent bootstrap completed.
)
pause
exit /b %RESULT%
