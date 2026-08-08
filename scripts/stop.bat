@echo off
chcp 65001 >nul 2>&1
:: ────────────────────────────────────────────────────────
:: Spectra — Script d'arrêt (Windows)
:: Usage: stop.bat [--clean]
::   --clean : supprime aussi les volumes Docker (données)
:: ────────────────────────────────────────────────────────

:: Opère sur la racine du dépôt (docker-compose sous deploy\docker\).
cd /d "%~dp0.."
setlocal enabledelayedexpansion

set COMPOSE=docker compose --project-directory . -f deploy/docker/docker-compose.yml

:: Meme invocation que start.bat, overlay GPU compris. Ce point comptait : « down »
:: construit la liste des conteneurs a supprimer a partir des fichiers qu'on lui donne, et
:: stop.bat n'incluait pas docker-compose.gpu.yml — il ne voyait donc pas exactement la
:: stack que start.bat avait demarree.
set "GPU_ENABLED="
for /f "tokens=1,* delims==" %%A in ('findstr /R /C:"^ *SPECTRA_GPU_ENABLED=" .env 2^>nul') do set "GPU_ENABLED=%%B"
if "!GPU_ENABLED!"=="true" set COMPOSE=!COMPOSE! -f deploy/docker/docker-compose.gpu.yml

:: Activer TOUS les profils pour que « down » stoppe aussi les services optionnels
:: (layout-parser, reranker, kafka, trainer) demarres via --profile ; --remove-orphans nettoie
:: les conteneurs restants du projet. Sans cela, « stop » laissait tourner ces
:: services : l'utilisateur croyait avoir tout arrete, mais leurs ports et leur
:: memoire restaient pris.
::
:: La liste est DEMANDEE a Compose au lieu d'etre recopiee ici. La copie codee en dur
:: fonctionnait tant que personne n'ajoutait de profil : le jour ou c'est arrive, le
:: service correspondant survivait a « stop » — port et memoire toujours pris, sans rien
:: pour le signaler puisque la commande sortait en succes.
set "PROFILES="
for /f "delims=" %%P in ('docker compose --project-directory . -f deploy/docker/docker-compose.yml config --profiles 2^>nul') do (
    if defined PROFILES ( set "PROFILES=!PROFILES!,%%P" ) else ( set "PROFILES=%%P" )
)
set "COMPOSE_PROFILES=!PROFILES!"
echo   profils couverts : !PROFILES!

echo ^> Arret des services Spectra...

if "%~1"=="--clean" (
    %COMPOSE% down -v --remove-orphans
    echo   [OK] Services arretes et volumes supprimes
) else (
    %COMPOSE% down --remove-orphans
    echo   [OK] Services arretes (volumes conserves^)
)

endlocal
