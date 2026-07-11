@echo off
setlocal enabledelayedexpansion

REM  ────────────────────────────────────────────────────────
REM  Spectra — Detection automatique de la configuration serveur
REM  Genere un fichier .env adapte aux ressources disponibles.
REM  Usage: detect-env.bat [--gpu]
REM  ────────────────────────────────────────────────────────

REM  Le .env est ancre a la racine du depot (a cote de .env.example, et lu par
REM  docker-compose via --project-directory .).
cd /d "%~dp0.."

set GPU_TYPE=none
set GPU_VRAM_MB=0

:parse_args
if "%~1"=="" goto done_args
if "%~1"=="--gpu" set GPU_TYPE=nvidia
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
set LLM_CONTEXT=512
if %TOTAL_RAM_MB% GEQ 8192 set LLM_CONTEXT=1024
if %TOTAL_RAM_MB% GEQ 16384 set LLM_CONTEXT=2048
if %TOTAL_RAM_MB% GEQ 32768 set LLM_CONTEXT=4096

REM  ── 6. Ecriture du .env ──
(
    echo # ── Spectra — Configuration auto-detectee ──
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
    echo LLM_CHAT_MODEL_FILE=Phi-4-mini-reasoning-UD-IQ1_S.gguf
    echo LLM_CHAT_MODEL_NAME=phi-4-mini
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
) > .env

echo.
echo   [OK] Fichier .env genere

endlocal
