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
:: (data\, .env, .env.example). %SCRIPT_DIR% pointe vers scripts\.
set "SCRIPT_DIR=%~dp0"
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
    "data\chroma"
) do (
    if not exist %%D\ (
        mkdir %%D
        echo   [CREE] %%D
    ) else (
        echo   [OK]   %%D
    )
)

:: ── 3. Fichier .env ────────────────────────────────────────────────────────
::
:: Ce script ne COPIE PLUS .env.example. La copie paraissait inoffensive ; elle ne
:: l'etait pas. .env.example est un CATALOGUE commente, mais il laisse 39 cles actives,
:: et une valeur posee dans .env l'emporte sur tout le reste. En copier le fichier
:: entier figeait LLM_PARALLEL=1 et LLM_CONTEXT=8192 — donc annulait le dimensionnement
:: calcule par detect-env.bat — et injectait dans le conteneur d'API
:: SPECTRA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 alors que le broker s'appelle
:: kafka:29092 sur le reseau Compose.
::
:: detect-env.bat est desormais le seul createur de .env ; .env.example reste la
:: reference ou l'on va chercher, a la ligne, ce qu'on veut surcharger.
echo.
echo ^> [3/6] Fichier de configuration .env...
if not exist ".env" (
    call "%SCRIPT_DIR%detect-env.bat"
    echo   [CREE] .env genere d'apres le materiel detecte
    echo   Options disponibles : voir .env.example ^(catalogue commente^).
) else (
    echo   [OK] .env existe deja — vos reglages sont conserves
)

:: ── 4. Modèle d'embedding ─────────────────────────────────────────────────
:: URL declarees UNE fois : elles sont reprises telles quelles dans les messages qui
:: expliquent le telechargement manuel. Deux copies divergeraient, et c'est celle que
:: l'utilisateur lit quand l'automatique a echoue — donc celle qui doit etre juste.
set "EMBED_MODEL_URL=https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf"
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
        call :fetch_file "%EMBED_MODEL_URL%" "data\models\embed.gguf"
        if errorlevel 1 (
            echo   [ERREUR] Echec du telechargement de embed.gguf
            echo   Le transfert partiel est conserve : relancez pour reprendre.
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
        echo     curl -L %EMBED_MODEL_URL% -o data\models\embed.gguf
        set /a ERRORS+=1
    )
)

:: ── 5. Modèle de chat ─────────────────────────────────────────────────────
:: Le modele doit resider dans data\models\ sous le nom que la stack Docker lit
:: (data\models\%%LLM_CHAT_MODEL_FILE%%), sinon llm-chat ne le trouve pas et attend
:: indefiniment : la stack demarre, mais le chat ne repond jamais.
:: Miroir de la section 6 de setup.sh.
set "CHAT_DOWNLOAD_NAME=Qwen2.5-7B-Instruct-Q4_K_M.gguf"
set "CHAT_MODEL_URL=https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/%CHAT_DOWNLOAD_NAME%"
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
        echo   Telechargement de %CHAT_DOWNLOAD_NAME% (~4,7 Go^)...
        echo   (plusieurs minutes selon votre connexion ; une coupure se reprend au relancement^)
        call :fetch_file "%CHAT_MODEL_URL%" "data\models\%CHAT_DOWNLOAD_NAME%"
        if errorlevel 1 (
            echo   [ERREUR] Echec du telechargement de %CHAT_DOWNLOAD_NAME%
            echo   Le transfert partiel est conserve : relancez pour reprendre.
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
        echo     curl -L %CHAT_MODEL_URL% ^
        echo       -o data\models\%CHAT_DOWNLOAD_NAME%
        echo.
        echo   Option 3 — Tout modele GGUF instruction-tuned fonctionne :
        echo     placez votre fichier dans data\models\ et renseignez
        echo     LLM_CHAT_MODEL_FILE=^<nom-du-fichier.gguf^> dans .env
        set /a ERRORS+=1
    )
)

:: ── 6. Artefact ONNX du reranker ──────────────────────────────────────────
:: Miroir de la section 7 de setup.sh. Le reranking est ACTIF par defaut : son artefact
:: fait partie de la pile par defaut et est recupere s'il manque, sans option.
:: Defaut d'URL : un asset de release DU PROJET, sur un tag dedie — l'artefact ne change
:: que si le MODELE change, pas a chaque version applicative.
set "RERANKER_ONNX_URL_DEFAULT=https://github.com/devdownin/SpectraLLM/releases/download/reranker-onnx-v1"
set "RERANKER_DIR=data\models\reranker"
set "RERANKER_ENABLED="
if exist ".env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
        if "%%A"=="SPECTRA_RERANKER_ONNX_URL" if "!SPECTRA_RERANKER_ONNX_URL!"=="" set "SPECTRA_RERANKER_ONNX_URL=%%B"
        if "%%A"=="SPECTRA_RERANKER_ENABLED"  set "RERANKER_ENABLED=%%B"
    )
)
if "!SPECTRA_RERANKER_ONNX_URL!"=="" set "SPECTRA_RERANKER_ONNX_URL=!RERANKER_ONNX_URL_DEFAULT!"
rem  Absent de .env = defaut applicatif, donc actif.
if "!RERANKER_ENABLED!"=="" set "RERANKER_ENABLED=true"

echo.
echo ^> [6/6] Artefact ONNX du reranker (!RERANKER_DIR!^)...
if exist "!RERANKER_DIR!\model.onnx" if exist "!RERANKER_DIR!\tokenizer.json" (
    echo   [OK] artefact present
    goto :reranker_done
)
set "RERANKER_WANTED=0"
if !DOWNLOAD_RERANKER!==1 set "RERANKER_WANTED=1"
if "!RERANKER_ENABLED!"=="true" set "RERANKER_WANTED=1"
if "!RERANKER_WANTED!"=="0" (
    echo   [OK] non requis — SPECTRA_RERANKER_ENABLED=!RERANKER_ENABLED!
    echo   Pour recuperer l'artefact malgre tout : scripts\setup.bat --download-reranker
    goto :reranker_done
)
if not exist "!RERANKER_DIR!\" mkdir "!RERANKER_DIR!"
echo   Recuperation de l'artefact (~0,5 Go^) depuis :
echo     !SPECTRA_RERANKER_ONNX_URL!
set "RERANKER_OK=1"
rem  Meme sous-programme que les GGUF, pour la meme raison : un artefact a moitie
rem  telecharge ne doit pas rester sous son nom definitif. ONNX Runtime ne le rejetterait
rem  qu'au premier rerank, longtemps apres le setup.
call :fetch_file "!SPECTRA_RERANKER_ONNX_URL!/model.onnx" "!RERANKER_DIR!\model.onnx"
if errorlevel 1 set "RERANKER_OK=0"
call :fetch_file "!SPECTRA_RERANKER_ONNX_URL!/tokenizer.json" "!RERANKER_DIR!\tokenizer.json"
if errorlevel 1 set "RERANKER_OK=0"
if "!RERANKER_OK!"=="1" (
    echo   [OK] artefact telecharge
    goto :reranker_done
)
if !DOWNLOAD_RERANKER!==1 (
    rem  Demande EXPLICITE : l'echec doit etre une erreur, l'utilisateur l'a reclame.
    echo   [ERREUR] Echec du telechargement de l'artefact.
    echo   L'URL doit designer le REPERTOIRE contenant model.onnx et tokenizer.json,
    echo   pas l'un des deux fichiers. Verifiez SPECTRA_RERANKER_ONNX_URL.
    set /a ERRORS+=1
) else (
    rem  Recuperation AUTOMATIQUE : son echec ne doit pas faire echouer l'installation.
    echo   [INFO] artefact indisponible — le RAG fonctionnera sans reranking.
    echo   Sans consequence : /api/status en donnera la cause, et les reponses sont
    echo   servies dans l'ordre vectoriel.
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
:: Telecharge %1 vers %2, SANS JAMAIS laisser un resultat partiel a l'emplacement final.
:: Miroir de fetch_file() dans setup.sh, ou le raisonnement est detaille. En resume :
::   — le fichier definitif n'existe que s'il est COMPLET (telechargement vers .part, puis
::     move au succes). Sinon un transfert interrompu laissait un GGUF tronque que le
::     lancement suivant declarait « present », et la panne se manifestait ailleurs ;
::   — « -C - » reprend a l'octet ou l'on s'etait arrete : sur 4,7 Go, c'est la difference
::     entre reprendre 10 % et retelecharger 90 % ;
::   — « --fail » MANQUAIT ici alors que setup.sh l'a : sans lui, une 404 ou une page
::     d'erreur HTML etait enregistree comme si c'etait le modele, curl sortait en 0, et le
::     script annoncait « [OK] telecharge ». Un GGUF de 12 Ko contenant du HTML.
:fetch_file
set "FF_URL=%~1"
set "FF_DEST=%~2"
set "FF_PART=%~2.part"
for /l %%A in (1,1,3) do (
    curl -L --fail --progress-bar --retry 3 --retry-delay 2 -C - "!FF_URL!" -o "!FF_PART!"
    if not errorlevel 1 (
        move /y "!FF_PART!" "!FF_DEST!" >nul
        exit /b 0
    )
    if %%A lss 3 (
        echo   Echec du transfert — nouvelle tentative dans 3 s...
        timeout /t 3 /nobreak >nul
    )
)
:: Le .part est CONSERVE : la prochaine execution reprendra ou celle-ci s'est arretee.
exit /b 1

:set_env_var
if not exist ".env" type nul > ".env"
findstr /v /b /c:"%~1=" ".env" > ".env.setup.tmp"
>> ".env.setup.tmp" echo %~1=%~2
move /y ".env.setup.tmp" ".env" >nul
goto :eof
