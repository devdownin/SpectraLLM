@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: ────────────────────────────────────────────────────────────────
:: Spectra — Pipeline complet : Ingest → Dataset → Fine-tuning
::
:: Usage  : pipeline.bat [repertoire] [modele-base] [nom-modele] [--reset] [--packing] [--dpo]
:: Exemples :
::   pipeline.bat data\documents phi3
::   pipeline.bat data\documents phi3 spectra-autoroute
::   pipeline.bat data\documents mistral spectra-mistral-autoroute
::   pipeline.bat data\documents phi3 phi3-autoroute --reset       (repart de zero)
::   pipeline.bat data\documents phi3 phi3-dpo --dpo               (alignement DPO)
::   pipeline.bat data\documents phi3 phi3-fast --packing          (multipacking)
::
:: Arguments :
::   [repertoire]   Dossier source des documents (defaut: data\documents)
::   [modele-base]  Modele a enrichir : phi3 | mistral | llama3 (defaut: phi3)
::   [nom-modele]   Nom du modele enrichi (defaut: {modele-base}-autoroute)
::   --reset        Supprime l'adaptateur existant et repart d'un entrainement initial
::   --packing      Active le multipacking (concatenation des sequences courtes)
::   --dpo          Active l'alignement DPO (requiert une generation DPO prealable)
:: ────────────────────────────────────────────────────────────────

cd /d "%~dp0"

set API_URL=http://localhost:8080
set POLL_INTERVAL=5
set MAX_POLL_INGEST=120
set MAX_POLL_DATASET=240

if "%~1"=="" ( set SOURCE_DIR=data\documents ) else ( set SOURCE_DIR=%~1 )
if "%~2"=="" ( set BASE_MODEL=phi3 )           else ( set BASE_MODEL=%~2 )
if "%~3"=="" ( set MODEL_NAME=%BASE_MODEL%-autoroute ) else ( set MODEL_NAME=%~3 )

set RESET_ADAPTER=0
set PACKING_FLAG=
set DPO_FLAG=

for %%A in (%*) do (
    if "%%A"=="--reset"   set RESET_ADAPTER=1
    if "%%A"=="--packing" set PACKING_FLAG=--packing
    if "%%A"=="--dpo"     set DPO_FLAG=--dpo
)

set DATASET_FILE=data\fine-tuning\pipeline-export.jsonl
set ADAPTER_DIR=data\fine-tuning\pipeline-adapter
set MERGED_DIR=data\fine-tuning\pipeline-merged

echo ======================================
echo   Spectra — Pipeline complet
echo ======================================
echo.
echo   Repertoire   : %SOURCE_DIR%
echo   Modele base  : %BASE_MODEL%
echo   Modele final : %MODEL_NAME%
echo   Multipacking : !PACKING_FLAG!
echo   DPO          : !DPO_FLAG!
echo   API          : %API_URL%
echo.

:: ── 0a. Vérification des fichiers GGUF ───────────────────────────────────────
echo ^> [0/5] Verification des prerequis...

set GGUF_CHAT=data\fine-tuning\merged\model.gguf
set GGUF_EMBED=data\models\embed.gguf

if not exist "%GGUF_CHAT%" (
    echo   [ERREUR] Modele de chat introuvable : %GGUF_CHAT%
    echo   Placez un fichier GGUF instruction-tuned dans ce chemin, ou lancez :
    echo     setup.bat --download-chat
    exit /b 1
)
echo   [OK] Modele chat : %GGUF_CHAT%

if not exist "%GGUF_EMBED%" (
    echo   [ERREUR] Modele d'embedding introuvable : %GGUF_EMBED%
    echo   Lancez : setup.bat --download-embed
    exit /b 1
)
echo   [OK] Modele embed : %GGUF_EMBED%

:: ── 0b. Vérification Python ────────────────────────────────────────
echo ^> [0/5] Verification des dependances...
python --version >nul 2>&1
if errorlevel 1 (
    echo   [ERREUR] Python introuvable. Installez Python 3.10+.
    exit /b 1
)
python -c "import peft, transformers, trl" >nul 2>&1
if errorlevel 1 (
    echo   [ERREUR] Dependances Python manquantes.
    echo   Executez : pip install peft transformers trl accelerate bitsandbytes
    exit /b 1
)
echo   [OK] Python et dependances OK

:: ── 0b. Vérification API ─────────────────────────────────────────
set /a API_READY=0
for /l %%i in (1,1,20) do (
    if !API_READY!==0 (
        powershell -Command "try { Invoke-WebRequest -Uri '%API_URL%/actuator/health' -UseBasicParsing -TimeoutSec 3 | Out-Null; exit 0 } catch { exit 1 }" >nul 2>&1
        if !errorlevel!==0 (
            set /a API_READY=1
            echo   [OK] API prete
        ) else (
            if %%i==1 echo   Attente du demarrage de l'API...
            timeout /t 3 /nobreak >nul
        )
    )
)
if !API_READY!==0 (
    echo   [ERREUR] API inaccessible. Lancez start.bat d'abord.
    exit /b 1
)

:: ══════════════════════════════════════════════════════════════
:: ETAPE 1 — INGESTION
:: ══════════════════════════════════════════════════════════════
echo.
echo ^> [1/5] Ingestion des documents depuis %SOURCE_DIR%...

if not exist "%SOURCE_DIR%\" (
    echo   [ERREUR] Repertoire introuvable : %SOURCE_DIR%
    exit /b 1
)

set FILE_COUNT=0
set FILE_LIST=
for %%E in (pdf docx doc json xml txt zip) do (
    for %%F in ("%SOURCE_DIR%\*.%%E") do (
        if exist "%%F" (
            set /a FILE_COUNT+=1
            set FILE_LIST=!FILE_LIST! "%%F"
        )
    )
)

if !FILE_COUNT!==0 (
    echo   [ERREUR] Aucun document trouve dans %SOURCE_DIR%
    exit /b 1
)
echo   !FILE_COUNT! fichier(s) detecte(s)

set PS_INGEST=^
$paths = @(!FILE_LIST!); ^
$files = @(); ^
foreach ($p in $paths) { ^
    $p = $p.Trim('"'); ^
    if (Test-Path $p) { ^
        $files += @{ Name=[System.IO.Path]::GetFileName($p); Bytes=[System.IO.File]::ReadAllBytes($p) } ^
    } ^
}; ^
$boundary = [System.Guid]::NewGuid().ToString(); ^
$CRLF = [System.Text.Encoding]::UTF8.GetBytes("`r`n"); ^
$body = New-Object System.IO.MemoryStream; ^
foreach ($f in $files) { ^
    $hdr = "--$boundary`r`nContent-Disposition: form-data; name=`"files`"; filename=`"$($f.Name)`"`r`nContent-Type: application/octet-stream`r`n`r`n"; ^
    $hdrB = [System.Text.Encoding]::UTF8.GetBytes($hdr); ^
    $body.Write($hdrB,0,$hdrB.Length); ^
    $body.Write($f.Bytes,0,$f.Bytes.Length); ^
    $body.Write($CRLF,0,$CRLF.Length) ^
}; ^
$end = [System.Text.Encoding]::UTF8.GetBytes("--$boundary--`r`n"); ^
$body.Write($end,0,$end.Length); ^
try { ^
    $r = Invoke-WebRequest -Uri '%API_URL%/api/ingest' -Method POST -Body $body.ToArray() -ContentType "multipart/form-data; boundary=$boundary" -UseBasicParsing -TimeoutSec 120; ^
    ($r.Content | ConvertFrom-Json).taskId ^
} catch { Write-Host "ERROR:$($_.Exception.Message)"; exit 1 }

for /f "delims=" %%T in ('powershell -Command "!PS_INGEST!"') do set INGEST_TASK=%%T

if "!INGEST_TASK!"=="" (
    echo   [ERREUR] Pas de taskId retourne par l'API d'ingestion.
    exit /b 1
)
echo   TaskId : !INGEST_TASK!

set /a POLL=0
:poll_ingest
if !POLL! geq %MAX_POLL_INGEST% ( echo   [TIMEOUT] Ingestion trop longue. & exit /b 1 )
timeout /t %POLL_INTERVAL% /nobreak >nul
set /a POLL+=1
for /f "delims=" %%S in ('powershell -Command "try { $j=(Invoke-WebRequest -Uri '%API_URL%/api/ingest/!INGEST_TASK!' -UseBasicParsing -TimeoutSec 5).Content|ConvertFrom-Json; Write-Host $j.status '|' $j.chunksCreated } catch { Write-Host 'ERROR|0' }"') do set INR=%%S
for /f "tokens=1 delims=|" %%A in ("!INR!") do set INGEST_STATUS=%%A
for /f "tokens=2 delims=|" %%B in ("!INR!") do set INGEST_CHUNKS=%%B
set INGEST_STATUS=!INGEST_STATUS: =!
if "!INGEST_STATUS!"=="COMPLETED" goto ingest_done
if "!INGEST_STATUS!"=="FAILED"    ( echo   [ECHEC] Ingestion echouee. & exit /b 1 )
echo   Ingestion en cours... chunks: !INGEST_CHUNKS: =!
goto poll_ingest
:ingest_done
echo   [OK] Ingestion terminee — !INGEST_CHUNKS: =! chunks vectorises

:: ══════════════════════════════════════════════════════════════
:: ETAPE 2 — GENERATION DU DATASET
:: ══════════════════════════════════════════════════════════════
echo.
echo ^> [2/5] Generation du dataset d'entrainement (modele: %BASE_MODEL%)...

:: S'assurer que le modele de generation est bien le bon
powershell -Command "try { Invoke-WebRequest -Uri '%API_URL%/api/config/model' -Method POST -Body '{\"model\":\"%BASE_MODEL%\"}' -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5 | Out-Null } catch { exit 1 }" >nul 2>&1
if !errorlevel! neq 0 echo   [AVERT] Basculement vers %BASE_MODEL% echoue — modele actif inchange

for /f "delims=" %%T in ('powershell -Command "try { $r=Invoke-WebRequest -Uri '%API_URL%/api/dataset/generate' -Method POST -UseBasicParsing -TimeoutSec 30; ($r.Content|ConvertFrom-Json).taskId } catch { Write-Host 'ERROR' }"') do set DATASET_TASK=%%T

if "!DATASET_TASK!"=="" ( echo   [ERREUR] Echec du lancement de la generation. & exit /b 1 )
echo   TaskId : !DATASET_TASK!

set /a POLL=0
:poll_dataset
if !POLL! geq %MAX_POLL_DATASET% ( echo   [TIMEOUT] Generation trop longue. & exit /b 1 )
timeout /t %POLL_INTERVAL% /nobreak >nul
set /a POLL+=1
for /f "delims=" %%S in ('powershell -Command "try { $j=(Invoke-WebRequest -Uri '%API_URL%/api/dataset/generate/!DATASET_TASK!' -UseBasicParsing -TimeoutSec 5).Content|ConvertFrom-Json; Write-Host $j.status '|' $j.pairsGenerated } catch { Write-Host 'ERROR|0' }"') do set DSR=%%S
for /f "tokens=1 delims=|" %%A in ("!DSR!") do set DS_STATUS=%%A
for /f "tokens=2 delims=|" %%B in ("!DSR!") do set DS_PAIRS=%%B
set DS_STATUS=!DS_STATUS: =!
if "!DS_STATUS!"=="COMPLETED" goto dataset_done
if "!DS_STATUS!"=="FAILED"    ( echo   [ECHEC] Generation echouee. & exit /b 1 )
echo   Generation en cours... paires: !DS_PAIRS: =!
goto poll_dataset
:dataset_done
set DS_PAIRS_CLEAN=!DS_PAIRS: =!
if "!DS_PAIRS_CLEAN!"=="0" (
    echo   [ERREUR] Generation terminee mais 0 paires produites.
    echo   Verifiez que le modele de chat repond correctement :
    echo     curl -X POST %API_URL%/api/query -H "Content-Type: application/json" -d "{\"question\":\"test\"}"
    exit /b 1
)
echo   [OK] Dataset genere — !DS_PAIRS_CLEAN! paires d'entrainement

:: ══════════════════════════════════════════════════════════════
:: ETAPE 3 — EXPORT DU DATASET
:: ══════════════════════════════════════════════════════════════
echo.
echo ^> [3/5] Export du dataset...

if not exist "data\fine-tuning\" mkdir "data\fine-tuning"

powershell -Command "Invoke-WebRequest -Uri '%API_URL%/api/dataset/export' -Method POST -OutFile '%DATASET_FILE%' -UseBasicParsing -TimeoutSec 30" >nul 2>&1
if not exist "%DATASET_FILE%" (
    echo   [ERREUR] Export du dataset echoue.
    exit /b 1
)
for /f %%L in ('powershell -Command "(Get-Content \"%DATASET_FILE%\" | Measure-Object -Line).Lines"') do set DS_LINES=%%L
echo   [OK] Dataset exporte : %DATASET_FILE% (!DS_LINES! lignes)

:: ══════════════════════════════════════════════════════════════
:: ETAPE 4 — FINE-TUNING SUR L'HOTE
:: ══════════════════════════════════════════════════════════════
echo.
echo ^> [4/5] Fine-tuning du modele %BASE_MODEL% sur l'hote...
echo   Cette etape peut durer plusieurs minutes (CPU) ou quelques minutes (GPU).

:: Verification DPO : s'assurer que des paires DPO existent si --dpo est demande
if "!DPO_FLAG!"=="--dpo" (
    for /f "delims=" %%N in ('powershell -Command "try { $j=(Invoke-WebRequest -Uri '%API_URL%/api/dataset/dpo/stats' -UseBasicParsing -TimeoutSec 5).Content|ConvertFrom-Json; Write-Host $j.totalPairs } catch { Write-Host 0 }"') do set DPO_PAIRS=%%N
    set DPO_PAIRS=!DPO_PAIRS: =!
    if "!DPO_PAIRS!"=="0" (
        echo   [ERREUR] --dpo demande mais aucune paire DPO trouvee.
        echo   Lancez d'abord la generation DPO :
        echo     curl -X POST %API_URL%/api/dataset/dpo/generate
        exit /b 1
    )
    echo   [OK] !DPO_PAIRS! paires DPO disponibles
)

set RESUME_FLAG=
if !RESET_ADAPTER!==1 (
    echo   [INFO] --reset : suppression de l'adaptateur existant
    if exist "%ADAPTER_DIR%\" rmdir /s /q "%ADAPTER_DIR%"
    echo   [INFO] Entrainement initial
) else if exist "%ADAPTER_DIR%\adapter_config.json" (
    echo   [INFO] Adaptateur existant detecte : entrainement incremental
    set RESUME_FLAG=--resume-adapter "%ADAPTER_DIR%"
) else (
    echo   [INFO] Aucun adaptateur existant : entrainement initial
)

python scripts\train_host.py ^
    --dataset "%DATASET_FILE%" ^
    --output  "%ADAPTER_DIR%" ^
    --base-model %BASE_MODEL% ^
    %RESUME_FLAG% ^
    --epochs 1 ^
    --lora-rank 8 ^
    %PACKING_FLAG% ^
    %DPO_FLAG%

if errorlevel 1 (
    echo   [ECHEC] Fine-tuning echoue.
    exit /b 1
)
echo   [OK] Fine-tuning termine — adaptateur : %ADAPTER_DIR%

:: ══════════════════════════════════════════════════════════════
:: ETAPE 5 — EXPORT GGUF + IMPORT OLLAMA + ACTIVATION
:: ══════════════════════════════════════════════════════════════
echo.
echo ^> [5/5] Export GGUF et import dans Ollama (%MODEL_NAME%)...

python scripts\export_gguf.py ^
    --adapter    "%ADAPTER_DIR%" ^
    --output     "%MERGED_DIR%" ^
    --base-model %BASE_MODEL% ^
    --model-name %MODEL_NAME%

if errorlevel 1 (
    echo   [ECHEC] Export GGUF ou import Ollama echoue.
    exit /b 1
)
echo   [OK] Modele %MODEL_NAME% importe dans Ollama

:: Basculer le modele actif de l'API vers le modele enrichi
powershell -Command "try { Invoke-WebRequest -Uri '%API_URL%/api/config/model' -Method POST -Body '{\"model\":\"%MODEL_NAME%\"}' -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5 | Out-Null } catch { exit 1 }" >nul 2>&1
if !errorlevel! neq 0 (
    echo   [AVERT] Basculement du modele echoue — modele actif inchange
) else (
    echo   [OK] Modele actif de l'API bascule vers %MODEL_NAME%
)

:: ══════════════════════════════════════════════════════════════
:: RÉSUMÉ
:: ══════════════════════════════════════════════════════════════
echo.
echo ======================================
echo   [OK] Pipeline termine avec succes !
echo.
echo   Modele de base enrichi : %BASE_MODEL%
echo   Modele final (Ollama)  : %MODEL_NAME%
echo   Modele actif de l'API  : %MODEL_NAME%
echo.
echo   Tester :
echo     ollama run %MODEL_NAME%
echo.
echo     curl -X POST %API_URL%/api/query ^
echo       -H "Content-Type: application/json" ^
echo       -d "{\"question\": \"Votre question...\"}"
echo.
echo   Swagger : %API_URL%/swagger-ui.html
echo ======================================
echo.

endlocal
