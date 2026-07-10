@echo off

setlocal enabledelayedexpansion

REM  ────────────────────────────────────────────────────────
REM  Spectra — Script de lancement (Windows)
REM  Usage: start.bat [--first-run] [--detach] [--gpu]
REM
REM    --first-run   Premier lancement tout-en-un : configuration initiale,
REM                  telechargement des modeles, demarrage en arriere-plan
REM                  puis ouverture du navigateur sur l'UI.
REM  ────────────────────────────────────────────────────────

REM  Les scripts vivent dans scripts\ mais la stack (docker-compose, data\, .env)
REM  est ancree a la racine du depot. %SCRIPT_DIR% pointe vers scripts\.
set "SCRIPT_DIR=%~dp0"
cd /d "%~dp0.."

set COMPOSE=docker compose --project-directory . -f deploy/docker/docker-compose.yml

set DETACH=
set GPU_FLAG=
set FIRST_RUN=

:parse_args
if "%~1"=="" goto done_args
if "%~1"=="--detach" set DETACH=-d
if "%~1"=="-d"       set DETACH=-d
if "%~1"=="--gpu" set GPU_FLAG=--gpu
if "%~1"=="--first-run" (
    set FIRST_RUN=1
    set DETACH=-d
)
shift
goto parse_args
:done_args

echo ======================================
echo         Spectra — Demarrage
echo ======================================

REM  0. Premier lancement : setup complet (repertoires, .env, modeles)
if defined FIRST_RUN (
    echo.
    echo ^> Premier lancement : configuration initiale + telechargement des modeles...
    call "%SCRIPT_DIR%setup.bat" --download-embed --download-chat
)

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
call "%SCRIPT_DIR%detect-env.bat" %GPU_FLAG%

REM  Lire si GPU active dans .env (racine du depot)
findstr /C:"SPECTRA_GPU_ENABLED=true" .env >nul 2>&1
if !errorlevel!==0 (
    set COMPOSE=!COMPOSE! -f deploy/docker/docker-compose.gpu.yml
    echo   [OK] GPU active, docker-compose.gpu.yml inclus
)

REM  3. Build si l'image n'existe pas
docker image inspect spectra-spectra-api >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo ^> Image spectra-api non trouvee, build en cours...
    !COMPOSE! build
)

REM  En mode premier plan, docker compose bloque le terminal : afficher les
REM  URLs d'acces AVANT le demarrage, sinon l'utilisateur ne les voit jamais.
if "%DETACH%"=="" (
    echo.
    echo ^> URLs d'acces ^(une fois les services prets, ~1-2 min^) :
    echo    Interface Web :  http://localhost
    echo    API REST      :  http://localhost:8080/api/status
    echo    Ctrl+C pour arreter — ou relancez avec --detach pour liberer le terminal.
)

REM  4. Demarrage des services
echo.
echo ^> Demarrage des services Docker...
%COMPOSE% up %DETACH%

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

REM  Interface Web (nginx + React)
set /a "READY=0"
for /l %%i in (1,1,30) do (
    if !READY!==0 (
        powershell -Command "try { Invoke-WebRequest -Uri http://localhost/ -UseBasicParsing -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }" >nul 2>&1
        if !errorlevel!==0 (
            echo   Interface Web: [OK] prete
            set /a "READY=1"
        ) else (
            timeout /t 2 /nobreak >nul
        )
    )
)
if !READY!==0 echo   Interface Web: [TIMEOUT]

REM  6. Resume
echo.
echo ======================================
echo  Spectra est pret !
echo.
echo  Interface Web :  http://localhost
echo.
echo  API REST    :  http://localhost:8080/api/status
echo  Swagger     :  http://localhost:8080/swagger-ui.html
echo  LLM server  :  http://localhost:8081
echo  ChromaDB    :  http://localhost:8000
echo.
echo  Arret       :  scripts\stop.bat
echo  Logs        :  %COMPOSE% logs -f
echo ======================================

REM  7. Premier lancement : ouvrir le navigateur sur l'UI
if defined FIRST_RUN start "" http://localhost

:eof
endlocal
