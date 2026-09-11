@echo off
REM 2-hour FarmGuard soak. Do not stop early and claim the Beta.1 soak gate.
cd /d "%~dp0.."
python scripts\phase15_soak.py
exit /b %ERRORLEVEL%
