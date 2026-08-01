"""
Contrat du service d'entraînement, vu par son client Java.

Ce qui est vérifié ici a un pendant exact dans `HttpTrainingRunnerTest` côté JVM : les deux
suites décrivent les deux moitiés du même contrat, et la sentinelle `__EXIT__:` en est le
point de contact. Si l'une des deux dérive, l'entraînement échoue en production sans qu'aucune
ne rougisse — d'où les valeurs littérales répétées de part et d'autre plutôt qu'une constante
partagée impossible à avoir entre deux langages.
"""

import pytest

EXIT_PREFIX = "__EXIT__:"


def _lines(response):
    return [line for line in response.text.splitlines() if line]


def _train_body(**overrides):
    body = {
        "jobId": "job-1",
        "datasetFile": "/app/data/ft/dataset.jsonl",
        "adapterPath": "/app/data/ft/adapter",
        "baseHfRepo": "microsoft/Phi-3-mini-4k-instruct",
        "loraRank": 64,
        "loraAlpha": 128,
        "epochs": 3,
        "learningRate": 0.0002,
        "packing": True,
        "dpo": False,
        "orpo": True,
        "valSplit": 0.1,
    }
    body.update(overrides)
    return body


# ── Santé ────────────────────────────────────────────────────────────────────

def test_health_reports_script_presence(client, echo_script):
    # C'est ce que le client interroge pour décider d'accepter une soumission : un trainer
    # joignable mais sans ses scripts doit se déclarer inutilisable ICI, pas à mi-entraînement.
    body = client(train_script=echo_script).get("/health").json()

    assert body["status"] == "ok"
    assert body["trainScript"] is True
    assert body["activeJobs"] == 0


def test_health_reports_missing_script(client, tmp_path):
    body = client(train_script=tmp_path / "absent.sh").get("/health").json()

    assert body["trainScript"] is False


# ── Entraînement ─────────────────────────────────────────────────────────────

def test_train_passes_eleven_positional_arguments_in_order(client, echo_script):
    """L'ordre suit l'en-tête de train.sh ($1..$11) — un décalage entraînerait sur d'autres
    hyperparamètres que ceux demandés, sans que rien n'échoue."""
    response = client(train_script=echo_script).post("/train", json=_train_body())

    args = [line[len("ARG:"):] for line in _lines(response) if line.startswith("ARG:")]
    assert args == [
        "/app/data/ft/dataset.jsonl",
        "/app/data/ft/adapter",
        "microsoft/Phi-3-mini-4k-instruct",
        "64", "128", "3", "0.0002",
        "true", "false", "true", "0.1",
    ]


def test_train_ends_with_exit_sentinel(client, echo_script):
    response = client(train_script=echo_script).post("/train", json=_train_body())

    assert _lines(response)[-1] == f"{EXIT_PREFIX}0"


def test_train_reports_non_zero_exit_and_merges_stderr(client, failing_script):
    # stderr fusionné dans stdout : sans cela, la cause d'un échec d'entraînement
    # n'atteindrait jamais les journaux présentés à l'utilisateur.
    lines = _lines(client(train_script=failing_script).post("/train", json=_train_body()))

    assert "torch introuvable" in lines
    assert lines[-1] == f"{EXIT_PREFIX}42"


def test_train_without_script_returns_503(client, tmp_path):
    # 503 et non 500 : l'image est mal construite ou mal montée, rien n'est « cassé ».
    response = client(train_script=tmp_path / "absent.sh").post("/train", json=_train_body())

    assert response.status_code == 503
    assert "introuvable" in response.json()["detail"]


@pytest.mark.parametrize("field,value", [
    ("loraRank", 0),        # ge=1
    ("epochs", 0),          # ge=1
    ("learningRate", 0),    # gt=0
    ("valSplit", 0.9),      # le=0.5
])
def test_train_rejects_out_of_range_hyperparameters(client, echo_script, field, value):
    # Rejeter en 422 plutôt que de lancer un entraînement voué à produire n'importe quoi.
    response = client(train_script=echo_script).post("/train", json=_train_body(**{field: value}))

    assert response.status_code == 422


# ── Export ───────────────────────────────────────────────────────────────────

def test_export_passes_named_options(client, echo_py_script):
    response = client(export_script=echo_py_script).post("/export", json={
        "jobId": "job-2",
        "adapterPath": "/app/data/ft/adapter",
        "outputDir": "/app/data/ft/merged",
        "modelName": "mon-modele",
        "baseHfRepo": "microsoft/Phi-3-mini-4k-instruct",
    })

    # L'export utilise des options NOMMÉES, contrairement à l'entraînement : le script les lit
    # par argparse, l'ordre n'y a donc pas d'importance, mais leur présence si.
    text = response.text
    for option in ("--adapter", "--output", "--model-name", "--base-model"):
        assert option in text
    assert _lines(response)[-1].startswith(EXIT_PREFIX)


# ── Annulation ───────────────────────────────────────────────────────────────

def test_cancel_unknown_job_is_not_an_error(client, echo_script):
    # Idempotent : le client annule sans savoir si un travail court encore.
    response = client(train_script=echo_script).post("/cancel/inexistant")

    assert response.status_code == 200
    assert response.json() == {"cancelled": False}


def test_closing_the_stream_kills_the_process(slow_script, tmp_path, monkeypatch):
    """
    Un flux abandonné ne doit pas laisser un entraînement orphelin.

    C'est le mode de panne le plus coûteux de ce service : un processus qui continue à
    consommer CPU et RAM après le départ du client, invisible et inarrêtable — le conteneur
    devrait être redémarré pour s'en débarrasser.

    Le générateur est piloté directement plutôt qu'à travers un client HTTP : c'est le
    mécanisme lui-même — le bloc `finally` de `_stream`, exécuté à la fermeture du générateur —
    qui porte la garantie. Passer par le client de test mesurerait surtout sa politique de
    tamponnage.
    """
    import app as app_module

    monkeypatch.setattr(app_module, "WORK_DIR", tmp_path)
    stream = app_module._stream("job-x", [str(slow_script)])

    next(stream)                                    # démarre le processus, lit sa 1re ligne
    assert "job-x" in app_module._processes
    process = app_module._processes["job-x"]

    stream.close()                                  # le client s'en va

    assert app_module._processes == {}, "le job reste enregistré après la fermeture du flux"
    assert process.poll() is not None, "le processus continue de tourner, orphelin"
