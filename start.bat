@echo off

setlocal enabledelayedexpansion

REM  ────────────────────────────────────────────────────────
REM  Spectra — Script de lancement (Windows)
REM  Usage: start.bat [--detach] [--gpu]
REM  ────────────────────────────────────────────────────────

cd /d "%~dp0"

set DETACH=
set GPU_FLAG=

:parse_args
if "%~1"=="" goto done_args
if "%~1"=="--detach" set DETACH=-d
if "%~1"=="-d"       set DETACH=-d
if "%~1"=="--gpu" set GPU_FLAG=--gpu
shift
goto parse_args
:done_args

echo ======================================
echo         Spectra — Demarrage
echo ======================================

REM  1. Creer les repertoires de donnees
echo.
echo ^> Creation des repertoires de donnees...
if not exist "data\documents"    mkdir "data\documents"
if not exist "data\dataset"      mkdir "data\dataset"
if not exist "data\fine-tuning"  mkdir "data\fine-tuning"
if not exist "data\models"       mkdir "data\models"
echo   [OK] data\documents, data\dataset, data\fine-tuning, data\models

REM  2. Detection automatique de la configuration serveur
echo.
echo ^> Detection de la configuration serveur...
call detect-env.bat %GPU_FLAG%

REM  Lire si GPU active dans .env
set COMPOSE_FILES=-f docker-compose.yml
findstr /C:"SPECTRA_GPU_ENABLED=true" .env >nul 2>&1
if !errorlevel!==0 (
    set COMPOSE_FILES=-f docker-compose.yml -f docker-compose.gpu.yml
    echo   [OK] GPU active, docker-compose.gpu.yml inclus
)

REM  3. Build si l'image n'existe pas
docker image inspect spectra-spectra-api >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo ^> Image spectra-api non trouvee, build en cours...
    docker compose %COMPOSE_FILES% build
)

REM  4. Demarrage des services
echo.
echo ^> Demarrage des services Docker...
docker compose %COMPOSE_FILES% up %DETACH%

REM  Si mode detache, on continue avec le post-setup
if "%DETACH%"=="" goto eof

echo   [OK] Services demarres en arriere-plan

REM  5. Attente que les services soient prets
echo.
echo ^> Attente des services...

REM  Serveur LLM
set /a "READY=0"
for /l %%i in (1,1,30) do (
    if !READY!==0 (
        powershell -Command "try { Invoke-WebRequest -Uri http://localhost:8081/health -UseBasicParsing -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }" >nul 2>&1
        if !errorlevel!==0 (
            echo   LLM server:   [OK] pret
            set /a "READY=1"
        ) else (
            timeout /t 2 /nobreak >nul
        )
    )
)
if !READY!==0 echo   LLM server:   [TIMEOUT]

REM  ChromaDB
set /a "READY=0"
for /l %%i in (1,1,30) do (
    if !READY!==0 (
        powershell -Command "try { Invoke-WebRequest -Uri http://localhost:8000/api/v1/heartbeat -UseBasicParsing -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }" >nul 2>&1
        if !errorlevel!==0 (
            echo   ChromaDB:     [OK] pret
            set /a "READY=1"
        ) else (
            timeout /t 2 /nobreak >nul
        )
    )
)
if !READY!==0 echo   ChromaDB:     [TIMEOUT]

REM  Spectra API
set /a "READY=0"
for /l %%i in (1,1,30) do (
    if !READY!==0 (
        powershell -Command "try { Invoke-WebRequest -Uri http://localhost:8080/actuator/health -UseBasicParsing -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }" >nul 2>&1
        if !errorlevel!==0 (
            echo   Spectra API:  [OK] pret
            set /a "READY=1"
        ) else (
            timeout /t 2 /nobreak >nul
        )
    )
)
if !READY!==0 echo   Spectra API:  [TIMEOUT]

REM  6. Resume
echo.
echo ======================================
echo  Spectra est pret !
echo.
echo  API REST    :  http://localhost:8080/api/status
echo  Swagger     :  http://localhost:8080/swagger-ui.html
echo  LLM server  :  http://localhost:8081
echo  ChromaDB    :  http://localhost:8000
echo.
echo  Arret       :  stop.bat
echo  Logs        :  docker compose logs -f
echo ======================================

:eof
endlocal
