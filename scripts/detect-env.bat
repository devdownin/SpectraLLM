@echo off
setlocal enabledelayedexpansion

REM  ────────────────────────────────────────────────────────
REM  Spectra — Detection automatique de la configuration serveur
REM  Ecrit dans .env un bloc de reglages adaptes aux ressources disponibles.
REM  Usage: detect-env.bat [--gpu] [--force]
REM
REM    --gpu    Force la detection NVIDIA (tests sans carte physique).
REM    --force  Repart d'un .env vierge : la zone utilisateur est sauvegardee
REM             dans .env.bak puis effacee.
REM
REM  COMMENT CE SCRIPT COHABITE AVEC VOS REGLAGES
REM  Le fichier .env est coupe en deux : un BLOC AUTO en tete, regenere a chaque
REM  execution, puis tout le reste — votre zone, jamais touchee. Compose retenant la
REM  DERNIERE affectation d'une cle, ce que vous ecrivez sous le bloc l'emporte.
REM
REM  La version precedente ecrasait .env ENTIEREMENT a chaque appel — donc a chaque
REM  start.bat, puisqu'il appelle ce script. Tout reglage saisi a la main disparaissait
REM  au demarrage suivant, sans avertissement. Le jumeau shell, lui, ne touchait a rien :
REM  les deux plateformes traitaient le meme fichier de deux facons opposees.
REM  ────────────────────────────────────────────────────────

REM  Le .env est ancre a la racine du depot (a cote de .env.example, et lu par
REM  docker-compose via --project-directory .).
cd /d "%~dp0.."

set GPU_TYPE=none
set GPU_VRAM_MB=0
set FORCE=0

REM  Bornes du bloc regenerable — ASCII pur, identiques a l'octet pres a celles de
REM  detect-env.sh, pour qu'un depot pilote tantot depuis WSL tantot depuis PowerShell
REM  n'empile pas deux blocs concurrents.
set "AUTO_BEGIN=# === SPECTRA:AUTO:BEGIN - bloc regenere par detect-env, ne pas editer ==="
set "AUTO_END=# === SPECTRA:AUTO:END - ecrivez vos reglages SOUS cette ligne ==="

:parse_args
if "%~1"=="" goto done_args
if "%~1"=="--gpu" set GPU_TYPE=nvidia
if "%~1"=="--force" set FORCE=1
shift
goto parse_args
:done_args

REM  ── 1. Detection RAM disponible (en Mo) ──
REM  FreePhysicalMemory (KB) = equivalent Windows de MemAvailable :
REM  reflete la marge reelle sur un serveur deja charge.
for /f "tokens=2 delims==" %%a in ('wmic OS get FreePhysicalMemory /value 2^>nul ^| find "="') do (
    set "RAM_FREE_KB=%%a"
)
for /f %%a in ('powershell -Command "[math]::Floor(%RAM_FREE_KB% / 1024)"') do set TOTAL_RAM_MB=%%a

REM  ── 2. Detection CPU ──
set CPU_CORES=%NUMBER_OF_PROCESSORS%

REM  ── 3. Detection GPU (NVIDIA via nvidia-smi, AMD via le nom du controleur video) ──
if not "%GPU_TYPE%"=="none" goto gpu_done

where nvidia-smi >nul 2>&1
if %errorlevel%==0 (
    nvidia-smi >nul 2>&1
    if !errorlevel!==0 (
        set GPU_TYPE=nvidia
        for /f %%a in ('nvidia-smi --query-gpu^=memory.total --format^=csv^,noheader^,nounits 2^>nul') do (
            if "!GPU_VRAM_MB!"=="0" set GPU_VRAM_MB=%%a
        )
    )
)

if "%GPU_TYPE%"=="none" (
    wmic path win32_VideoController get name 2>nul | findstr /i "AMD Radeon" >nul 2>&1
    if !errorlevel!==0 set GPU_TYPE=amd
)

:gpu_done
set GPU_DETECTED=false
if not "%GPU_TYPE%"=="none" set GPU_DETECTED=true

REM  ── 4. Calcul du profil ──
set PROFILE=medium
if %TOTAL_RAM_MB% LSS 8192 set PROFILE=small
if %TOTAL_RAM_MB% GEQ 24576 set PROFILE=large

echo ^> Detection du serveur :
echo   RAM dispo  : %TOTAL_RAM_MB% Mo
echo   CPU cores  : %CPU_CORES%
echo   GPU        : %GPU_TYPE% (VRAM: %GPU_VRAM_MB% Mo)
echo   Profil     : %PROFILE%

REM  ── 5. Calcul des parametres pipeline ──
if "%PROFILE%"=="small" (
    set CHUNK_MAX_TOKENS=256
    set CHUNK_OVERLAP_TOKENS=32
    set EMBEDDING_BATCH_SIZE=5
    set EMBEDDING_TIMEOUT=60
    set GENERATION_TIMEOUT=180
    set CONCURRENT_INGESTIONS=1
    set /a JVM_HEAP=%TOTAL_RAM_MB% / 4
)
if "%PROFILE%"=="medium" (
    set CHUNK_MAX_TOKENS=512
    set CHUNK_OVERLAP_TOKENS=64
    set EMBEDDING_BATCH_SIZE=5
    set EMBEDDING_TIMEOUT=30
    set GENERATION_TIMEOUT=120
    set CONCURRENT_INGESTIONS=1
    set /a JVM_HEAP=%TOTAL_RAM_MB% / 4
)
if "%PROFILE%"=="large" (
    set CHUNK_MAX_TOKENS=512
    set CHUNK_OVERLAP_TOKENS=64
    set EMBEDDING_BATCH_SIZE=5
    set EMBEDDING_TIMEOUT=30
    set GENERATION_TIMEOUT=120
    set CONCURRENT_INGESTIONS=1
    set /a JVM_HEAP=%TOTAL_RAM_MB% / 3
)

REM  Plafonner le heap JVM a 4 Go et garantir un minimum de 2 Go
if %JVM_HEAP% LSS 2048 set JVM_HEAP=2048
if %JVM_HEAP% GTR 4096 set JVM_HEAP=4096

REM  Serveur LLM : nombre de requetes paralleles (min 1, max 8)
set /a LLM_PARALLEL=%CPU_CORES% / 2
if %LLM_PARALLEL% LSS 1 set LLM_PARALLEL=1
if %LLM_PARALLEL% GTR 8 set LLM_PARALLEL=8

REM  Taille de contexte LLM — memes seuils que detect-env.sh / ResourceAdvisorService
REM LLM_CONTEXT est le contexte TOTAL, reparti entre les slots paralleles : une
REM requete ne voit que LLM_CONTEXT / LLM_PARALLEL tokens. On dimensionne donc la
REM fenetre PAR REQUETE, puis on en deduit le total, en plafonnant le parallelisme
REM pour tenir l'enveloppe memoire. (Avant : plus de coeurs = fenetre plus petite.)
set SLOT_CONTEXT=1024
set MAX_TOTAL_CONTEXT=2048
if %TOTAL_RAM_MB% GEQ 8192 ( set SLOT_CONTEXT=2048& set MAX_TOTAL_CONTEXT=4096 )
if %TOTAL_RAM_MB% GEQ 16384 ( set SLOT_CONTEXT=4096& set MAX_TOTAL_CONTEXT=16384 )
if %TOTAL_RAM_MB% GEQ 32768 ( set SLOT_CONTEXT=8192& set MAX_TOTAL_CONTEXT=32768 )
set /a MAX_SLOTS=%MAX_TOTAL_CONTEXT% / %SLOT_CONTEXT%
if %MAX_SLOTS% LSS 1 set MAX_SLOTS=1
if %LLM_PARALLEL% GTR %MAX_SLOTS% set LLM_PARALLEL=%MAX_SLOTS%
set /a LLM_CONTEXT=%SLOT_CONTEXT% * %LLM_PARALLEL%

REM  ── 6. Ecriture du .env ──
REM
REM  Zone utilisateur : tout ce qui, dans le .env actuel, n'a pas ete ecrit par ce script.
REM  Elle est mise de cote AVANT reecriture, puis reemise telle quelle sous le bloc auto.
set "USER_ZONE=%TEMP%\spectra-env-user-%RANDOM%.tmp"
set "MIGRATED="
type nul > "%USER_ZONE%"

if not exist ".env" goto zone_ready

if "%FORCE%"=="1" (
    copy /y ".env" ".env.bak" >nul
    set "MIGRATED=--force : ancien contenu sauvegarde dans .env.bak"
    goto zone_ready
)

REM  Cas courant : ne garder que ce qui suit la borne de fin. findstr /N donne son numero
REM  de ligne, « more +N » saute les N premieres.
set "END_LINE="
for /f "delims=:" %%N in ('findstr /N /C:"SPECTRA:AUTO:END" ".env" 2^>nul') do set "END_LINE=%%N"
if defined END_LINE (
    more +%END_LINE% ".env" > "%USER_ZONE%" 2>nul
    goto zone_ready
)

REM  Fichier entierement genere par une version anterieure : le garder en zone utilisateur
REM  figerait une detection perimee, et la masquerait pour toujours puisque la derniere
REM  affectation gagne. On le remplace, avec copie de sauvegarde.
set "FIRST_LINE="
set /p FIRST_LINE=< ".env"
set "LEGACY="
echo !FIRST_LINE!| findstr /C:"Configuration auto" >nul 2>&1 && set "LEGACY=1"
if defined LEGACY (
    copy /y ".env" ".env.bak" >nul
    set "MIGRATED=ancien .env auto-genere remplace ^(copie dans .env.bak^)"
    goto zone_ready
)

REM  .env ecrit a la main, ou copie depuis .env.example : integralement a l'utilisateur.
copy /y ".env" "%USER_ZONE%" >nul

:zone_ready

REM  Une zone vide recoit un en-tete annonce, pour que l'endroit ou ecrire ses propres
REM  reglages soit visible sans lire ce script.
for %%Z in ("%USER_ZONE%") do set "ZONE_SIZE=%%~zZ"
if "%ZONE_SIZE%"=="0" (
    (
        echo.
        echo # ── Vos reglages ──
        echo # Ajoutez ici vos surcharges : elles l'emportent sur le bloc auto ci-dessus.
        echo # Le catalogue complet des variables, commente, est dans .env.example — recopiez-en
        echo # les lignes qui vous interessent plutot que le fichier entier, dont les valeurs
        echo # figeraient a la fois la detection materielle et les defauts de docker-compose.yml.
    ) > "%USER_ZONE%"
)

(
    echo %AUTO_BEGIN%
    echo # Profil: %PROFILE% ^| RAM: %TOTAL_RAM_MB% Mo ^| CPU: %CPU_CORES% cores ^| GPU: %GPU_TYPE% ^(VRAM: %GPU_VRAM_MB% Mo^)
    echo.
    echo # ── Pipeline ──
    echo SPECTRA_CHUNK_MAX_TOKENS=%CHUNK_MAX_TOKENS%
    echo SPECTRA_CHUNK_OVERLAP_TOKENS=%CHUNK_OVERLAP_TOKENS%
    echo SPECTRA_EMBEDDING_BATCH_SIZE=%EMBEDDING_BATCH_SIZE%
    echo SPECTRA_EMBEDDING_TIMEOUT=%EMBEDDING_TIMEOUT%
    echo SPECTRA_GENERATION_TIMEOUT=%GENERATION_TIMEOUT%
    echo SPECTRA_CONCURRENT_INGESTIONS=%CONCURRENT_INGESTIONS%
    echo.
    echo # ── JVM ──
    echo JAVA_OPTS="-Xms256m -Xmx%JVM_HEAP%m -XX:+UseZGC"
    echo.
    echo # ── Serveur LLM — Chat ──
    echo LLM_CHAT_MODEL_FILE=Qwen2.5-7B-Instruct-Q4_K_M.gguf
    echo LLM_CHAT_MODEL_NAME=qwen2.5-7b-instruct
    echo LLM_PARALLEL=%LLM_PARALLEL%
    echo LLM_CONTEXT=%LLM_CONTEXT%
    echo.
    echo # ── Serveur LLM — Embedding ──
    echo LLM_EMBED_MODEL_FILE=embed.gguf
    echo LLM_EMBED_MODEL_NAME=nomic-embed-text
    echo LLM_EMBED_PARALLEL=%LLM_PARALLEL%
    echo.
    echo # ── Provider LLM ──
    echo SPECTRA_LLM_PROVIDER=llama-cpp
    echo.
    echo # ── GPU ──
    echo SPECTRA_GPU_ENABLED=%GPU_DETECTED%
    echo SPECTRA_GPU_TYPE=%GPU_TYPE%
    echo SPECTRA_GPU_VRAM_MB=%GPU_VRAM_MB%
    echo %AUTO_END%
) > ".env.new"

type "%USER_ZONE%" >> ".env.new"
move /y ".env.new" ".env" >nul
del /q "%USER_ZONE%" >nul 2>&1

echo.
echo   [OK] Bloc auto-detecte ecrit dans .env
if defined MIGRATED echo   [OK] !MIGRATED!

endlocal
