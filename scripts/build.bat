@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
:: ────────────────────────────────────────────────────────
:: Spectra — Script de build (Windows)
:: Usage: build.bat [--skip-tests]
:: ────────────────────────────────────────────────────────

cd /d "%~dp0"

set SKIP_TESTS=
if "%~1"=="--skip-tests" set SKIP_TESTS=-DskipTests

echo ======================================
echo         Spectra — Build
echo ======================================

:: 1. Build Maven (si mvn est disponible localement)
where mvn >nul 2>&1
if %errorlevel%==0 (
    echo.
    echo ^> Maven build...
    call mvn clean package %SKIP_TESTS% -B -q
    if !errorlevel! neq 0 (
        echo   [ERREUR] Build Maven echoue.
        exit /b 1
    )
    echo   [OK] JAR construit: target\spectra-api-0.1.0-SNAPSHOT.jar
) else (
    echo.
    echo ^> Maven non trouve localement, le build sera fait dans Docker.
)

:: 2. Build Docker
echo.
echo ^> Docker build...
docker compose build --no-cache
if %errorlevel% neq 0 (
    echo   [ERREUR] Build Docker echoue.
    exit /b 1
)
echo   [OK] Images Docker construites

echo.
echo ======================================
echo  Build termine.
echo  Lancez: start.bat
echo ======================================

endlocal
