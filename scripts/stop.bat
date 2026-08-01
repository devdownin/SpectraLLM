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

:: Activer TOUS les profils pour que « down » stoppe aussi les services optionnels
:: (layout-parser, reranker, kafka, trainer) demarres via --profile ; --remove-orphans nettoie
:: les conteneurs restants du projet. Sans cela, « stop » laissait tourner ces
:: services : l'utilisateur croyait avoir tout arrete, mais leurs ports et leur
:: memoire restaient pris. stop.sh le faisait deja.
set COMPOSE_PROFILES=layout-parser,reranker,kafka,trainer

echo ^> Arret des services Spectra...

if "%~1"=="--clean" (
    %COMPOSE% down -v --remove-orphans
    echo   [OK] Services arretes et volumes supprimes
) else (
    %COMPOSE% down --remove-orphans
    echo   [OK] Services arretes (volumes conserves^)
)
