@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
:: ────────────────────────────────────────────────────────
:: Spectra — Script de build (Windows)
:: Usage: build.bat [--skip-tests] [--no-cache]
::
::   --skip-tests  Ne joue pas les tests Maven.
::   --no-cache    Reconstruit les images en ignorant le cache de couches.
::                 A reserver au diagnostic : c'est plusieurs minutes de plus,
::                 le temps de retelecharger les dependances Maven et npm.
:: ────────────────────────────────────────────────────────

:: Les scripts vivent dans scripts\ mais opèrent sur la racine du dépôt
:: (pom sous backend\, docker-compose sous deploy\docker\).
cd /d "%~dp0.."

set COMPOSE=docker compose --project-directory . -f deploy/docker/docker-compose.yml

set SKIP_TESTS=
set NO_CACHE=
for %%A in (%*) do (
    if "%%A"=="--skip-tests" set SKIP_TESTS=-DskipTests
    if "%%A"=="--no-cache"   set NO_CACHE=--no-cache
)

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
    :: Le nom du JAR etait annonce en dur : a la premiere montee de version, le script
    :: designait un fichier inexistant. On lit ce qui a ete produit.
    set "JAR="
    for %%J in ("backend\target\*.jar") do if not defined JAR set "JAR=%%~nxJ"
    if defined JAR ( echo   [OK] JAR construit: backend\target\!JAR! ) else ( echo   [OK] Build Maven termine )
) else (
    echo.
    echo ^> Maven non trouve localement, le build sera fait dans Docker.
)

:: 2. Build Docker
echo.
echo ^> Docker build...

:: Le cache de couches n'est plus contourne par defaut. « --no-cache » systematique
:: retelechargeait l'integralite des dependances Maven et npm a chaque appel — plusieurs
:: minutes pour une virgule changee — et annulait au passage le cache de depot Maven que
:: le Dockerfile monte precisement pour eviter cela. Docker sait deja invalider ce qui a
:: change ; l'option reste disponible pour les cas ou on soupconne le cache lui-meme.
%COMPOSE% build %NO_CACHE%
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
