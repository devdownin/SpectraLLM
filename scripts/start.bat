@echo off

setlocal enabledelayedexpansion

REM  ────────────────────────────────────────────────────────
REM  Spectra — Script de lancement (Windows)
REM  Usage: start.bat [--first-run] [--detach] [--gpu] [--trainer] [--build]
REM
REM    --first-run   Premier lancement tout-en-un : configuration initiale,
REM                  telechargement des modeles, demarrage en arriere-plan
REM                  puis ouverture du navigateur sur l'UI.
REM    --trainer     Rend le fine-tuning executable : demarre le service
REM                  spectra-trainer et branche l'API dessus. Sans cela, une
REM                  soumission est refusee — l'image spectra-api est une JRE
REM                  sans Python, elle ne peut pas lancer scripts/train.sh.
REM                  L'image du trainer pese plusieurs Go (torch).
REM    --build       Reconstruit les images avant de demarrer. Sans cette option,
REM                  une image deja construite est reutilisee telle quelle : apres
REM                  une modification du code, c'est l'ANCIENNE qui redemarre.
REM  ────────────────────────────────────────────────────────

REM  Les scripts vivent dans scripts\ mais la stack (docker-compose, data\, .env)
REM  est ancree a la racine du depot. %SCRIPT_DIR% pointe vers scripts\.
set "SCRIPT_DIR=%~dp0"
cd /d "%~dp0.."

set COMPOSE=docker compose --project-directory . -f deploy/docker/docker-compose.yml

set DETACH=
set GPU_FLAG=
set FIRST_RUN=
set TRAINER=
set BUILD=

:parse_args
if "%~1"=="" goto done_args
if "%~1"=="--detach" set DETACH=-d
if "%~1"=="-d"       set DETACH=-d
if "%~1"=="--gpu" set GPU_FLAG=--gpu
if "%~1"=="--trainer" set TRAINER=1
if "%~1"=="--build" set BUILD=--build
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
    REM  Pas de --download-reranker : le reranking etant actif par defaut, setup.bat
    REM  recupere son artefact de lui-meme s'il manque, et traite l'echec comme une
    REM  information et non comme une erreur. Miroir de start.sh.
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

REM  Lire si GPU active dans .env (racine du depot). La DERNIERE affectation fait foi :
REM  c'est la regle qu'applique Compose, et celle qui permet a un reglage personnel place
REM  sous le bloc auto de detect-env.bat de l'emporter sur ce qui a ete detecte.
set "GPU_ENABLED="
for /f "tokens=1,* delims==" %%A in ('findstr /R /C:"^ *SPECTRA_GPU_ENABLED=" .env 2^>nul') do set "GPU_ENABLED=%%B"
if "!GPU_ENABLED!"=="true" (
    set COMPOSE=!COMPOSE! -f deploy/docker/docker-compose.gpu.yml
    echo   [OK] GPU active, docker-compose.gpu.yml inclus
)

REM  2 bis. Fine-tuning en conteneur. Miroir de start.sh, ou le raisonnement est detaille.
REM  En resume : le profil compose « trainer » demarre le service qui sait executer
REM  scripts/train.sh, et SPECTRA_FINE_TUNING_RUNNER=http dit a l'API de s'adresser a lui.
REM  N'en poser qu'un donne les deux pannes symetriques — « script d'entrainement
REM  introuvable » d'un cote, « service d'entrainement injoignable » de l'autre.
if defined TRAINER goto enable_trainer
findstr /R /C:"^ *SPECTRA_FINE_TUNING_RUNNER= *http" .env >nul 2>&1
if not !errorlevel!==0 goto no_trainer

:enable_trainer
set "SPECTRA_FINE_TUNING_RUNNER=http"
REM  Fusion et non affectation : un COMPOSE_PROFILES deja renseigne (reranker, kafka…)
REM  serait sinon ecrase, et demander le trainer desactiverait le reste sans le dire.
if defined COMPOSE_PROFILES goto have_profiles
for /f "tokens=1,* delims==" %%A in ('findstr /R /C:"^ *COMPOSE_PROFILES=" .env 2^>nul') do set "COMPOSE_PROFILES=%%B"
:have_profiles
echo ,!COMPOSE_PROFILES!,| findstr /C:",trainer," >nul
if !errorlevel!==0 goto profiles_ready
if defined COMPOSE_PROFILES (
    set "COMPOSE_PROFILES=!COMPOSE_PROFILES!,trainer"
) else (
    set "COMPOSE_PROFILES=trainer"
)
:profiles_ready
echo.
echo ^> Fine-tuning en conteneur active
echo   [OK] service spectra-trainer + SPECTRA_FINE_TUNING_RUNNER=http
echo        Premiere fois : l'image embarque torch, comptez plusieurs Go et un long build.
:no_trainer

REM  3. Build si l'image n'existe pas, ou sur demande explicite.
REM  On interroge COMPOSE plutot qu'un nom d'image code en dur : « spectra-spectra-api »
REM  supposait un projet nomme « spectra », alors que Compose le derive du repertoire
REM  (« spectrallm-spectra-api » pour un clone standard). L'inspection echouait donc
REM  toujours, et chaque start.bat relancait un build complet dont il n'avait pas besoin.
if defined BUILD goto build_ready
set "HAVE_IMAGE="
for /f "delims=" %%I in ('!COMPOSE! images -q spectra-api 2^>nul') do set "HAVE_IMAGE=1"
if not defined HAVE_IMAGE (
    set BUILD=--build
    echo.
    echo ^> Image spectra-api non trouvee, elle sera construite au demarrage.
)
:build_ready

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
REM
REM  « --wait » remplace les cinq boucles PowerShell qui vivaient ici. Elles interrogeaient
REM  des URLs codees en dur alors que CHAQUE service declare deja son healthcheck dans le
REM  fichier Compose : deux definitions du « pret » pour un meme service, dont une seule
REM  fait autorite. Elles ne couvraient d'ailleurs que quatre services sur neuf — un
REM  reranker ou un docparser en echec passait inapercu jusqu'a la premiere requete — et un
REM  depassement de delai n'affichait qu'un « [TIMEOUT] » cosmetique : le recapitulatif
REM  « Spectra est pret ! » s'affichait quand meme, sur une stack en panne.
echo.
echo ^> Demarrage des services Docker...

if "%DETACH%"=="" (
    %COMPOSE% up %BUILD%
    goto eof
)

set STARTED=1
REM  Le delai couvre le start_period le plus long de la stack (llm-chat, 120 s) augmente du
REM  chargement effectif d'un modele 7B.
%COMPOSE% up -d --wait --wait-timeout 300 %BUILD%
if !errorlevel! neq 0 set STARTED=0

if "!STARTED!"=="1" (
    echo   [OK] Tous les services sont sains
) else (
    echo.
    echo   [ATTENTION] Un ou plusieurs services ne sont pas devenus sains dans le delai imparti.
    %COMPOSE% ps
    echo.
    echo   Diagnostic : %COMPOSE% logs --tail=50
    echo   Un modele GGUF absent de data\models\ est la cause la plus frequente :
    echo   llm-chat et llm-embed attendent alors le fichier au lieu de servir.
)

REM  6. Resume
echo.
echo ======================================
if "!STARTED!"=="1" (
    echo  Spectra est pret !
) else (
    echo  Spectra a demarre partiellement.
)
echo.
echo  Interface Web :  http://localhost
echo.
echo  API REST    :  http://localhost:8080/api/status
echo  Swagger     :  http://localhost:8080/swagger-ui.html
echo  LLM server  :  http://localhost:8081
echo  ChromaDB    :  http://localhost:8000
if "!SPECTRA_FINE_TUNING_RUNNER!"=="http" (
    echo  Trainer     :  http://localhost:8004/health
) else (
    echo.
    echo  Fine-tuning :  indisponible — relancez avec scripts\start.bat --trainer
)
echo.
echo  Arret       :  scripts\stop.bat
echo  Logs        :  %COMPOSE% logs -f
echo ======================================

REM  7. Premier lancement : ouvrir le navigateur sur l'UI
if defined FIRST_RUN if "!STARTED!"=="1" start "" http://localhost

if not "!STARTED!"=="1" (
    endlocal
    exit /b 1
)

:eof
endlocal
