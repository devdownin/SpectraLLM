@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: ────────────────────────────────────────────────────────────────────────────
:: Spectra — Script de configuration initiale (Windows)
::
:: Usage : setup.bat [--download-embed] [--download-chat]
::
::   --download-embed   Télécharge nomic-embed-text (~81 Mo) si absent
::   --download-chat    Télécharge Phi-3.5-mini Q4_K_M (~2.4 Go) si absent
::
:: Ce script vérifie les prérequis et prépare l'environnement avant le
:: premier lancement. À exécuter une seule fois.
:: ────────────────────────────────────────────────────────────────────────────

cd /d "%~dp0"

set DOWNLOAD_EMBED=0
set DOWNLOAD_CHAT=0
set ERRORS=0

for %%A in (%*) do (
    if "%%A"=="--download-embed" set DOWNLOAD_EMBED=1
    if "%%A"=="--download-chat"  set DOWNLOAD_CHAT=1
)

echo ======================================
echo   Spectra — Configuration initiale
echo ======================================
echo.

:: ── 1. Docker ─────────────────────────────────────────────────────────────
echo ^> [1/5] Verification de Docker...
docker info >nul 2>&1
if errorlevel 1 (
    echo   [ERREUR] Docker n'est pas demarre ou n'est pas installe.
    echo   Installez Docker Desktop : https://www.docker.com/products/docker-desktop
    set /a ERRORS+=1
) else (
    for /f "tokens=*" %%V in ('docker --version 2^>nul') do echo   [OK] %%V
)

:: ── 2. Répertoires de données ──────────────────────────────────────────────
echo.
echo ^> [2/5] Creation des repertoires de donnees...
for %%D in (
    "data\documents"
    "data\dataset"
    "data\fine-tuning"
    "data\fine-tuning\merged"
    "data\models"
    "data\source"
) do (
    if not exist %%D\ (
        mkdir %%D
        echo   [CREE] %%D
    ) else (
        echo   [OK]   %%D
    )
)

:: ── 3. Fichier .env ────────────────────────────────────────────────────────
echo.
echo ^> [3/5] Fichier de configuration .env...
if not exist ".env" (
    if exist ".env.example" (
        copy ".env.example" ".env" >nul
        echo   [CREE] .env copie depuis .env.example
        echo   Editez .env pour personnaliser la configuration.
    ) else (
        echo   [AVERT] .env.example introuvable — ignoré
    )
) else (
    echo   [OK] .env existe deja
)

:: ── 4. Modèle d'embedding ─────────────────────────────────────────────────
echo.
echo ^> [4/5] Modele d'embedding (data\models\embed.gguf)...
if exist "data\models\embed.gguf" (
    for %%S in ("data\models\embed.gguf") do (
        set /a SIZE_MB=%%~zS / 1048576
        echo   [OK] embed.gguf present — !SIZE_MB! Mo
    )
) else (
    if !DOWNLOAD_EMBED!==1 (
        echo   Telechargement de nomic-embed-text-v1.5.Q4_K_M.gguf (~81 Mo^)...
        curl -L --progress-bar ^
            "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf" ^
            -o "data\models\embed.gguf"
        if errorlevel 1 (
            echo   [ERREUR] Echec du telechargement de embed.gguf
            set /a ERRORS+=1
        ) else (
            echo   [OK] embed.gguf telecharge
        )
    ) else (
        echo   [MANQUANT] data\models\embed.gguf absent
        echo.
        echo   Telechargez-le avec :
        echo     setup.bat --download-embed
        echo   Ou manuellement :
        echo     curl -L https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf -o data\models\embed.gguf
        set /a ERRORS+=1
    )
)

:: ── 5. Modèle de chat ─────────────────────────────────────────────────────
echo.
echo ^> [5/5] Modele de chat (data\fine-tuning\merged\model.gguf^)...
if exist "data\fine-tuning\merged\model.gguf" (
    for %%S in ("data\fine-tuning\merged\model.gguf") do (
        set /a SIZE_MB=%%~zS / 1048576
        echo   [OK] model.gguf present — !SIZE_MB! Mo
    )
) else (
    if !DOWNLOAD_CHAT!==1 (
        echo   Telechargement de Phi-3.5-mini-instruct-Q4_K_M.gguf (~2.4 Go^)...
        echo   (cela peut prendre plusieurs minutes selon votre connexion^)
        curl -L --progress-bar ^
            "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf" ^
            -o "data\fine-tuning\merged\model.gguf"
        if errorlevel 1 (
            echo   [ERREUR] Echec du telechargement de model.gguf
            set /a ERRORS+=1
        ) else (
            echo   [OK] model.gguf telecharge
        )
    ) else (
        echo   [MANQUANT] data\fine-tuning\merged\model.gguf absent
        echo.
        echo   Option 1 — Telechargement automatique (Phi-3.5-mini ~2.4 Go^) :
        echo     setup.bat --download-chat
        echo.
        echo   Option 2 — Telechargement manuel :
        echo     curl -L https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf ^
        echo       -o data\fine-tuning\merged\model.gguf
        echo.
        echo   Option 3 — Tout modele GGUF instruction-tuned fonctionne.
        echo     Placez votre fichier dans data\fine-tuning\merged\model.gguf
        set /a ERRORS+=1
    )
)

:: ── Résumé ─────────────────────────────────────────────────────────────────
echo.
echo ======================================
if !ERRORS!==0 (
    echo   [OK] Configuration terminee — tout est en place !
    echo.
    echo   Pour demarrer Spectra :
    echo     start.bat --detach
    echo.
    echo   Pour tester avec des exemples :
    echo     adddoc.bat examples
) else (
    echo   [!] Configuration incomplete — !ERRORS! element(s) a corriger.
    echo   Relancez setup.bat apres avoir resolu les problemes ci-dessus.
)
echo ======================================
echo.

endlocal
