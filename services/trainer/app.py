"""
Spectra Trainer Service
Exécute le fine-tuning QLoRA dans son propre conteneur, hors de l'image applicative.

Pourquoi ce service existe
--------------------------
L'entraînement QLoRA n'a pas d'équivalent Java crédible (cf. docs/process/audit-python-java.fr.md,
§8). Il ne peut donc pas être migré — mais il n'a pas non plus sa place dans `spectra-api`, dont
l'image est une JRE sans Python. Le résultat, jusqu'ici, était le pire des deux : l'API acceptait
les jobs puis échouait à mi-course (constat F1).

Ce service tranche autrement. L'entraînement redevient ce qu'il est réellement — un travail ML
batch, optionnel, à l'ordonnancement séparé — appelé par HTTP au lieu d'être lancé par
`ProcessBuilder` depuis la JVM.

Contrat
-------
`/train` et `/export` **diffusent** la sortie du script ligne à ligne, puis émettent une ligne
sentinelle `__EXIT__:<code>`. C'est ce qui permet au client Java de conserver exactement la
sémantique du mode hôte : progression au fil de l'eau, diffusion SSE, et code de sortie final.

L'absence de sentinelle est donc significative : elle signale une interruption (conteneur tué,
connexion rompue) et **doit** être traitée comme un échec côté client, jamais comme un succès.

Chemins
-------
Le client transmet des chemins ABSOLUS calculés dans le conteneur applicatif. Ce service doit
donc monter le même volume au même point de montage (`./data:/app/data`, WORKDIR `/app`).
Sans cette symétrie, l'entraînement démarrerait puis ne trouverait pas son jeu de données —
un échec tardif, exactement ce que ce service est censé supprimer.
"""
import logging
import os
import subprocess
from pathlib import Path
from typing import Dict, Iterator, List

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("trainer")

WORK_DIR = Path(os.getenv("TRAINER_WORK_DIR", "/app/data/fine-tuning"))
TRAIN_SCRIPT = Path(os.getenv("TRAINER_TRAIN_SCRIPT", "/app/scripts/train.sh"))
EXPORT_SCRIPT = Path(os.getenv("TRAINER_EXPORT_SCRIPT", "/app/scripts/export_gguf.py"))
PYTHON_BIN = os.getenv("TRAINER_PYTHON", "python3")

#: Préfixe de la ligne finale portant le code de sortie. Doit rester identique côté Java
#: (HttpTrainingRunner.EXIT_PREFIX) — c'est le seul canal du code de retour.
EXIT_PREFIX = "__EXIT__:"

#: Processus vivants, par job. C'est ce qui rend l'annulation effective.
_processes: Dict[str, subprocess.Popen] = {}


class TrainRequest(BaseModel):
    jobId: str
    datasetFile: str
    adapterPath: str
    baseHfRepo: str
    loraRank: int = Field(ge=1)
    loraAlpha: int = Field(ge=1)
    epochs: int = Field(ge=1)
    learningRate: float = Field(gt=0)
    packing: bool = False
    dpo: bool = False
    orpo: bool = False
    valSplit: float = Field(default=0.0, ge=0.0, le=0.5)


class ExportRequest(BaseModel):
    jobId: str
    adapterPath: str
    outputDir: str
    modelName: str
    baseHfRepo: str


app = FastAPI(title="Spectra Trainer", version="1.0.0")
Instrumentator().instrument(app).expose(app)


def _stream(job_id: str, command: List[str]) -> Iterator[str]:
    """
    Lance le sous-processus et diffuse sa sortie, sentinelle de sortie comprise.

    Le bloc `finally` est la partie qui compte : il s'exécute aussi quand le client se
    déconnecte (Starlette ferme alors le générateur). Sans lui, une connexion rompue laisserait
    un entraînement orphelin consommer CPU et RAM jusqu'à la fin de ses epochs, invisible et
    inarrêtable — le conteneur devrait être redémarré pour s'en débarrasser.
    """
    log.info("Job %s : %s", job_id, " ".join(command))
    process = subprocess.Popen(  # noqa: S603 - commande construite ici, pas reçue du client
        command,
        cwd=str(WORK_DIR),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    _processes[job_id] = process
    try:
        for line in process.stdout:  # type: ignore[union-attr]
            yield line if line.endswith("\n") else line + "\n"
        yield f"{EXIT_PREFIX}{process.wait()}\n"
    finally:
        _processes.pop(job_id, None)
        if process.poll() is None:
            log.warning("Job %s : flux interrompu — arrêt du processus.", job_id)
            process.kill()
            process.wait()


def _require(script: Path, label: str) -> None:
    if not script.is_file():
        # 503 et non 500 : rien n'est cassé, l'image est mal construite ou mal montée.
        raise HTTPException(
            status_code=503,
            detail=f"{label} introuvable : {script}. Vérifiez que scripts/ est bien copié "
                   f"dans l'image du trainer, ou TRAINER_TRAIN_SCRIPT/TRAINER_EXPORT_SCRIPT.",
        )


@app.post("/train")
def train(req: TrainRequest) -> StreamingResponse:
    """Lance l'entraînement. Arguments POSITIONNELS, dans l'ordre de l'en-tête de train.sh."""
    _require(TRAIN_SCRIPT, "script d'entraînement")
    command = [
        str(TRAIN_SCRIPT),
        req.datasetFile,
        req.adapterPath,
        req.baseHfRepo,
        str(req.loraRank),
        str(req.loraAlpha),
        str(req.epochs),
        str(req.learningRate),
        str(req.packing).lower(),
        str(req.dpo).lower(),
        str(req.orpo).lower(),
        str(req.valSplit),
    ]
    return StreamingResponse(_stream(req.jobId, command), media_type="text/plain")


@app.post("/export")
def export(req: ExportRequest) -> StreamingResponse:
    """Fusionne l'adaptateur LoRA et le convertit en GGUF."""
    _require(EXPORT_SCRIPT, "script d'export")
    command = [
        PYTHON_BIN, str(EXPORT_SCRIPT),
        "--adapter", req.adapterPath,
        "--output", req.outputDir,
        "--model-name", req.modelName,
        "--base-model", req.baseHfRepo,
    ]
    return StreamingResponse(_stream(req.jobId, command), media_type="text/plain")


@app.post("/cancel/{job_id}")
def cancel(job_id: str) -> dict:
    """Interrompt le travail en cours. Idempotent : annuler un job inconnu n'est pas une erreur."""
    process = _processes.get(job_id)
    if process is None or process.poll() is not None:
        return {"cancelled": False}
    process.kill()
    log.info("Job %s : interrompu à la demande.", job_id)
    return {"cancelled": True}


@app.get("/health")
def health() -> dict:
    """
    État du service. Les deux drapeaux de scripts sont ce que le client interroge pour décider
    d'accepter ou non une soumission — un trainer joignable mais sans ses scripts est
    indisponible, et doit le dire ici plutôt qu'à mi-entraînement.
    """
    return {
        "status": "ok",
        "trainScript": TRAIN_SCRIPT.is_file(),
        "exportScript": EXPORT_SCRIPT.is_file(),
        "activeJobs": len(_processes),
    }
