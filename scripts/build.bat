@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
:: ────────────────────────────────────────────────────────
:: Spectra — Script de build (Windows)
:: Usage: build.bat [--skip-tests]
:: ────────────────────────────────────────────────────────

:: Les scripts vivent dans scripts\ mais opèrent sur la racine du dépôt
:: (pom sous backend\, docker-compose sous deploy\docker\).
cd /d "%~dp0.."

set COMPOSE=docker compose --project-directory . -f deploy/docker/docker-compose.yml

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
    call mvn clean package %SKIP_TESTS% -B -q -f backend/pom.xml
    if !errorlevel! neq 0 (
        echo   [ERREUR] Build Maven echoue.
        exit /b 1
    )
    echo   [OK] JAR construit: backend\target\spectra-api-1.1.0-SNAPSHOT.jar
) else (
    echo.
    echo ^> Maven non trouve localement, le build sera fait dans Docker.
)

:: 2. Build Docker
echo.
echo ^> Docker build...
%COMPOSE% build --no-cache
if %errorlevel% neq 0 (
    echo   [ERREUR] Build Docker echoue.
    exit /b 1
)
echo   [OK] Images Docker construites

echo.
echo ======================================
echo  Build termine.
echo  Lancez: scripts\start.bat
echo ======================================

endlocal
