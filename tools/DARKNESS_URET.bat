@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ================================================
echo   Darkness - Otomatik eklenti uretici
echo ================================================
set PY=python
where python >nul 2>nul || set PY=py
set /p SITE=Site adresi (ornek https://siteadi.com): 
set /p HAR=HAR dosyasi (yoksa Enter): 
if "%HAR%"=="" (
  %PY% darkness_uretici.py "%SITE%"
) else (
  %PY% darkness_uretici.py "%SITE%" --har "%HAR%"
)
echo.
echo Zip ve rapor "cikti" klasorunde.
pause
