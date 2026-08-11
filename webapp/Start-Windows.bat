@echo off
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 (
  echo Bitte Node.js LTS installieren: https://nodejs.org
  start https://nodejs.org
  pause
  exit /b 1
)
echo Starte Verflixed Webapp...
call npx --yes electron@33.2.0 .
