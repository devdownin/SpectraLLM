@echo off
chcp 65001 >nul 2>&1
:: ────────────────────────────────────────────────────────
:: Spectra — Script d'arrêt (Windows)
:: Usage: stop.bat [--clean]
::   --clean : supprime aussi les volumes Docker (données)
:: ────────────────────────────────────────────────────────

:: Opère sur la racine du dépôt (docker-compose sous deploy\docker\).
cd /d "%~dp0.."

set COMPOSE=docker compose --project-directory . -f deploy/docker/docker-compose.yml

echo ^> Arret des services Spectra...

if "%~1"=="--clean" (
    %COMPOSE% down -v
    echo   [OK] Services arretes et volumes supprimes
) else (
    %COMPOSE% down
    echo   [OK] Services arretes (volumes conserves^)
)
