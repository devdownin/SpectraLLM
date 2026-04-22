@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: ────────────────────────────────────────────────────────────────
:: Spectra — Ingestion de documents
:: Usage  : adddoc.bat [repertoire]
:: Defaut : data\documents
:: Formats: PDF, DOCX, DOC, JSON, XML, TXT
:: ────────────────────────────────────────────────────────────────

cd /d "%~dp0"

set API_URL=http://localhost:8080
set INGEST_ENDPOINT=%API_URL%/api/ingest
set POLL_INTERVAL=3
set MAX_POLL=120

:: Répertoire source (argument ou défaut)
if "%~1"=="" (
    set SOURCE_DIR=data\documents
) else (
    set SOURCE_DIR=%~1
)

echo ======================================
echo   Spectra — Ingestion de documents
echo ======================================
echo.
echo   Repertoire : %SOURCE_DIR%
echo   API        : %INGEST_ENDPOINT%
echo.

:: ── 1. Vérification du répertoire ─────────────────────────────
if not exist "%SOURCE_DIR%\" (
    echo [ERREUR] Repertoire introuvable : %SOURCE_DIR%
    exit /b 1
)

:: ── 2. Vérification que l'API est accessible ──────────────────
echo ^> Verification de l'API Spectra...
set /a API_READY=0
for /l %%i in (1,1,20) do (
    if !API_READY!==0 (
        powershell -Command "try { $r = Invoke-WebRequest -Uri '%API_URL%/actuator/health' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
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
    echo   [ERREUR] API inaccessible apres 60s. Verifiez que start.bat a ete lance.
    exit /b 1
)

:: ── 3. Collecte des fichiers supportés ────────────────────────
echo.
echo ^> Recherche de documents dans %SOURCE_DIR%...

set FILE_COUNT=0
set FILE_LIST=

for %%E in (pdf docx doc json xml txt zip) do (
    for %%F in ("%SOURCE_DIR%\*.%%E") do (
        if exist "%%F" (
            set /a FILE_COUNT+=1
            echo   [%%!FILE_COUNT!] %%~nxF
            set FILE_LIST=!FILE_LIST! "%%F"
        )
    )
)

if !FILE_COUNT!==0 (
    echo   [AVERTISSEMENT] Aucun document trouve dans %SOURCE_DIR%
    echo   Formats supportes : PDF, DOCX, DOC, JSON, XML, TXT
    exit /b 0
)

echo.
echo   Total : !FILE_COUNT! fichier(s) a ingerer

:: ── 4. Construction de la requête multipart via PowerShell ────
echo.
echo ^> Envoi vers l'API d'ingestion...

set PS_SCRIPT=^
$files = @(); ^
$paths = @(!FILE_LIST!); ^
foreach ($p in $paths) { ^
    $p = $p.Trim('"'); ^
    if (Test-Path $p) { ^
        $bytes = [System.IO.File]::ReadAllBytes($p); ^
        $name  = [System.IO.Path]::GetFileName($p); ^
        $files += @{ Name=$name; Bytes=$bytes }; ^
    } ^
}; ^
$boundary = [System.Guid]::NewGuid().ToString(); ^
$LF = [System.Text.Encoding]::UTF8.GetBytes([char]10); ^
$CRLF = [System.Text.Encoding]::UTF8.GetBytes("`r`n"); ^
$body = New-Object System.IO.MemoryStream; ^
foreach ($f in $files) { ^
    $hdr = "--$boundary`r`nContent-Disposition: form-data; name=`"files`"; filename=`"$($f.Name)`"`r`nContent-Type: application/octet-stream`r`n`r`n"; ^
    $hdrBytes = [System.Text.Encoding]::UTF8.GetBytes($hdr); ^
    $body.Write($hdrBytes, 0, $hdrBytes.Length); ^
    $body.Write($f.Bytes, 0, $f.Bytes.Length); ^
    $body.Write($CRLF, 0, $CRLF.Length); ^
}; ^
$end = [System.Text.Encoding]::UTF8.GetBytes("--$boundary--`r`n"); ^
$body.Write($end, 0, $end.Length); ^
$bodyBytes = $body.ToArray(); ^
try { ^
    $resp = Invoke-WebRequest -Uri '%INGEST_ENDPOINT%' -Method POST -Body $bodyBytes -ContentType "multipart/form-data; boundary=$boundary" -UseBasicParsing -TimeoutSec 120; ^
    $resp.Content ^
} catch { ^
    Write-Host "[ERREUR] $($_.Exception.Message)"; ^
    exit 1 ^
}

for /f "delims=" %%R in ('powershell -Command "!PS_SCRIPT!"') do set RESPONSE=%%R

if "!RESPONSE!"=="" (
    echo   [ERREUR] Reponse vide de l'API.
    exit /b 1
)

echo   Reponse : !RESPONSE!

:: ── 5. Extraction du taskId ───────────────────────────────────
for /f "delims=" %%T in ('powershell -Command "$r = '!RESPONSE!' | ConvertFrom-Json; $r.taskId"') do set TASK_ID=%%T

if "!TASK_ID!"=="" (
    echo   [ERREUR] Impossible d'extraire le taskId.
    exit /b 1
)

echo   TaskId  : !TASK_ID!

:: ── 6. Suivi de la progression ────────────────────────────────
echo.
echo ^> Suivi de l'ingestion (taskId: !TASK_ID!)...

set /a POLL=0
set FINAL_STATUS=UNKNOWN

:poll_loop
if !POLL! geq %MAX_POLL% goto poll_timeout

timeout /t %POLL_INTERVAL% /nobreak >nul
set /a POLL+=1

for /f "delims=" %%S in ('powershell -Command "try { $r = Invoke-WebRequest -Uri '%INGEST_ENDPOINT%/!TASK_ID!' -UseBasicParsing -TimeoutSec 5; $j = $r.Content | ConvertFrom-Json; Write-Host $j.status '|' $j.chunksCreated } catch { Write-Host 'ERROR' }"') do set POLL_RESULT=%%S

for /f "tokens=1 delims=|" %%A in ("!POLL_RESULT!") do set CURRENT_STATUS=%%A
for /f "tokens=2 delims=|" %%B in ("!POLL_RESULT!") do set CHUNKS=%%B

set CURRENT_STATUS=!CURRENT_STATUS: =!
set CHUNKS=!CHUNKS: =!

if "!CURRENT_STATUS!"=="COMPLETED" (
    set FINAL_STATUS=COMPLETED
    goto poll_done
)
if "!CURRENT_STATUS!"=="FAILED" (
    set FINAL_STATUS=FAILED
    goto poll_done
)

echo   [!POLL!/!MAX_POLL!] Statut : !CURRENT_STATUS! — chunks : !CHUNKS!
goto poll_loop

:poll_timeout
echo   [TIMEOUT] L'ingestion depasse le delai d'attente.
exit /b 1

:poll_done

:: ── 7. Résultat final ─────────────────────────────────────────
echo.
echo ======================================
if "!FINAL_STATUS!"=="COMPLETED" (
    echo   [OK] Ingestion terminee avec succes
    echo   Chunks vectorises : !CHUNKS!
    echo.
    echo   Etape suivante :
    echo     POST %API_URL%/api/dataset/generate
    echo     puis POST %API_URL%/api/fine-tuning
) else (
    echo   [ECHEC] Ingestion echouee — verifiez les logs Docker :
    echo     docker compose logs spectra-api
)
echo ======================================
echo.

endlocal
