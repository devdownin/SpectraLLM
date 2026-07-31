@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: ────────────────────────────────────────────────────────────────────────────
:: Spectra — Script de configuration initiale (Windows)
::
:: Usage : setup.bat [--download-embed] [--download-chat] [--download-reranker]
::
::   --download-embed     Télécharge nomic-embed-text (~81 Mo) si absent
::   --download-chat      Télécharge Qwen2.5-7B-Instruct Q4_K_M (~4.7 Go) si absent
::   --download-reranker  Télécharge l'artefact ONNX du reranker (~0,5 Go) si absent.
::                        Optionnel : le reranking est désactivé par défaut.
::
:: Ce script vérifie les prérequis et prépare l'environnement avant le
:: premier lancement. À exécuter une seule fois.
:: ────────────────────────────────────────────────────────────────────────────

:: Les scripts vivent dans scripts\ mais operent sur la racine du depot
:: (data\, .env, .env.example).
cd /d "%~dp0.."

set DOWNLOAD_EMBED=0
set DOWNLOAD_CHAT=0
set DOWNLOAD_RERANKER=0
set ERRORS=0

for %%A in (%*) do (
    if "%%A"=="--download-embed"    set DOWNLOAD_EMBED=1
    if "%%A"=="--download-chat"     set DOWNLOAD_CHAT=1
    if "%%A"=="--download-reranker" set DOWNLOAD_RERANKER=1
)

echo ======================================
echo   Spectra — Configuration initiale
echo ======================================
echo.

:: ── 1. Docker ─────────────────────────────────────────────────────────────
echo ^> [1/6] Verification de Docker...
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
echo ^> [2/6] Creation des repertoires de donnees...
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
echo ^> [3/6] Fichier de configuration .env...
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
echo ^> [4/6] Modele d'embedding (data\models\embed.gguf)...
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
:: Le modele doit resider dans data\models\ sous le nom que la stack Docker lit
:: (data\models\%%LLM_CHAT_MODEL_FILE%%), sinon model-init / llm-chat ne le
:: trouvent pas — miroir de la section 6 de setup.sh.
set "CHAT_DOWNLOAD_NAME=Qwen2.5-7B-Instruct-Q4_K_M.gguf"
set "CHAT_MODEL_FILE="
set "CHAT_MODEL_NAME="
if exist ".env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
        if "%%A"=="LLM_CHAT_MODEL_FILE" set "CHAT_MODEL_FILE=%%B"
        if "%%A"=="LLM_CHAT_MODEL_NAME" set "CHAT_MODEL_NAME=%%B"
    )
)
if "!CHAT_MODEL_FILE!"=="" set "CHAT_MODEL_FILE=%CHAT_DOWNLOAD_NAME%"
echo.
echo ^> [5/6] Modele de chat (data\models\!CHAT_MODEL_FILE!^)...
if exist "data\models\!CHAT_MODEL_FILE!" (
    for %%S in ("data\models\!CHAT_MODEL_FILE!") do (
        set /a SIZE_MB=%%~zS / 1048576
        echo   [OK] !CHAT_MODEL_FILE! present — !SIZE_MB! Mo
    )
    call :set_env_var LLM_CHAT_MODEL_FILE "!CHAT_MODEL_FILE!"
) else (
    if !DOWNLOAD_CHAT!==1 (
        echo   Telechargement de %CHAT_DOWNLOAD_NAME% (~4.7 Go^)...
        echo   (cela peut prendre plusieurs minutes selon votre connexion^)
        curl -L --progress-bar ^
            "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf" ^
            -o "data\models\%CHAT_DOWNLOAD_NAME%"
        if errorlevel 1 (
            echo   [ERREUR] Echec du telechargement de %CHAT_DOWNLOAD_NAME%
            set /a ERRORS+=1
        ) else (
            echo   [OK] %CHAT_DOWNLOAD_NAME% telecharge
            rem Aligner .env pour que la stack Docker charge bien ce fichier.
            call :set_env_var LLM_CHAT_MODEL_FILE "%CHAT_DOWNLOAD_NAME%"
            echo   LLM_CHAT_MODEL_FILE=%CHAT_DOWNLOAD_NAME% ecrit dans .env
            rem Aucune reecriture d'alias : ce script telecharge desormais le modele
            rem PAR DEFAUT du projet, dont l'alias par defaut est deja correct.
        )
    ) else (
        echo   [MANQUANT] data\models\!CHAT_MODEL_FILE! absent
        echo.
        echo   Option 1 — Telechargement automatique (Qwen2.5-7B-Instruct ~4.7 Go^) :
        echo     setup.bat --download-chat
        echo.
        echo   Option 2 — Telechargement manuel :
        echo     curl -L https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf ^
        echo       -o data\models\%CHAT_DOWNLOAD_NAME%
        echo.
        echo   Option 3 — Tout modele GGUF instruction-tuned fonctionne :
        echo     placez votre fichier dans data\models\ et renseignez
        echo     LLM_CHAT_MODEL_FILE=^<nom-du-fichier.gguf^> dans .env
        set /a ERRORS+=1
    )
)

:: ── 6. Artefact ONNX du reranker (optionnel) ──────────────────────────────
:: Miroir de la section 7 de setup.sh. Le reranking est DESACTIVE par defaut : un
:: artefact absent n'est pas une erreur, sauf si la configuration pretend l'utiliser.
set "RERANKER_DIR=data\models\reranker"
if "%SPECTRA_RERANKER_ONNX_URL%"=="" (
    if exist ".env" (
        for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
            if "%%A"=="SPECTRA_RERANKER_ONNX_URL" set "SPECTRA_RERANKER_ONNX_URL=%%B"
            if "%%A"=="SPECTRA_RERANKER_ENABLED"  set "RERANKER_ENABLED=%%B"
            if "%%A"=="SPECTRA_RERANKER_ENGINE"   set "RERANKER_ENGINE=%%B"
        )
    )
)
echo.
echo ^> [6/6] Artefact ONNX du reranker (!RERANKER_DIR!^) — optionnel...
if exist "!RERANKER_DIR!\model.onnx" if exist "!RERANKER_DIR!\tokenizer.json" (
    echo   [OK] artefact present
    goto :reranker_done
)
if !DOWNLOAD_RERANKER!==1 (
    if "!SPECTRA_RERANKER_ONNX_URL!"=="" (
        echo   [ERREUR] SPECTRA_RERANKER_ONNX_URL n'est pas defini.
        echo   Cette variable donne l'URL de BASE du repertoire contenant model.onnx
        echo   et tokenizer.json. Renseignez-la dans .env.
        echo.
        echo   Pour produire l'artefact vous-meme depuis un modele deja en cache, voir
        echo   docs\process\audit-python-java.fr.md ^(section 6.3 bis^).
        set /a ERRORS+=1
    ) else (
        if not exist "!RERANKER_DIR!\" mkdir "!RERANKER_DIR!"
        echo   Telechargement depuis !SPECTRA_RERANKER_ONNX_URL! (~0,5 Go^)...
        rem --fail : une 404 doit echouer, pas laisser une page HTML nommee model.onnx.
        curl -L --fail --progress-bar "!SPECTRA_RERANKER_ONNX_URL!/model.onnx" -o "!RERANKER_DIR!\model.onnx"
        if errorlevel 1 (
            echo   [ERREUR] Echec du telechargement de model.onnx
            set /a ERRORS+=1
        ) else (
            curl -L --fail --progress-bar "!SPECTRA_RERANKER_ONNX_URL!/tokenizer.json" -o "!RERANKER_DIR!\tokenizer.json"
            if errorlevel 1 (
                echo   [ERREUR] Echec du telechargement de tokenizer.json
                set /a ERRORS+=1
            ) else (
                echo   [OK] artefact telecharge
                echo   Activez-le avec SPECTRA_RERANKER_ENABLED=true et SPECTRA_RERANKER_ENGINE=onnx
            )
        )
    )
) else (
    if "!RERANKER_ENABLED!"=="true" if "!RERANKER_ENGINE!"=="onnx" (
        echo   [AVERT] reranking active en moteur « onnx » mais l'artefact est absent.
        echo   Le reranking echouera et le RAG retombera sur l'ordre vectoriel.
        echo   Corrigez avec : scripts\setup.bat --download-reranker
        goto :reranker_done
    )
    echo   [OK] non requis — reranking desactive (defaut^)
    echo   Pour l'activer : scripts\setup.bat --download-reranker
)
:reranker_done

:: ── Résumé ─────────────────────────────────────────────────────────────────
echo.
echo ======================================
if !ERRORS!==0 (
    echo   [OK] Configuration terminee — tout est en place !
    echo.
    echo   Pour demarrer Spectra :
    echo     scripts\start.bat --detach
    echo.
    echo   Pour tester avec des exemples :
    echo     scripts\adddoc.bat examples
) else (
    echo   [!] Configuration incomplete — !ERRORS! element(s) a corriger.
    echo   Relancez setup.bat apres avoir resolu les problemes ci-dessus.
)
echo ======================================
echo.

endlocal
exit /b 0

:: ── Sous-routine : insère ou met à jour une variable KEY=VALUE dans .env ────
:: Miroir de set_env_var de setup.sh. Usage : call :set_env_var CLE "valeur"
:set_env_var
if not exist ".env" type nul > ".env"
findstr /v /b /c:"%~1=" ".env" > ".env.setup.tmp"
>> ".env.setup.tmp" echo %~1=%~2
move /y ".env.setup.tmp" ".env" >nul
goto :eof
