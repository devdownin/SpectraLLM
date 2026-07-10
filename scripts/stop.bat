@echo off
chcp 65001 >nul 2>&1
:: ────────────────────────────────────────────────────────
:: Spectra — Script d'arrêt (Windows)
:: Usage: stop.bat [--clean]
::   --clean : supprime aussi les volumes Docker (données)
:: ────────────────────────────────────────────────────────

cd /d "%~dp0"

echo ^> Arret des services Spectra...

if "%~1"=="--clean" (
    docker compose down -v
    echo   [OK] Services arretes et volumes supprimes
) else (
    docker compose down
    echo   [OK] Services arretes (volumes conserves^)
)
