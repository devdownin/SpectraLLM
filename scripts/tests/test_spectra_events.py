"""
Format des évènements structurés émis par l'entraînement.

C'est un CONTRAT entre deux langages : ce que produit `spectra_events` doit être exactement ce
que lit `FineTuningService` côté Java. Le format d'avant — de la prose relue par expressions
régulières — n'était écrit nulle part, et deux de ses trois analyseurs se sont révélés faux.
Ces tests le fixent d'un côté ; `FineTuningProgressTrackingTest` le fixe de l'autre.
"""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import spectra_events  # noqa: E402


def payload(line):
    """Décompose une ligne d'évènement : préfixe attendu, puis JSON."""
    assert line.startswith(spectra_events.SENTINEL + " "), line
    return json.loads(line[len(spectra_events.SENTINEL) + 1:])


def test_progress_carries_the_fractional_epoch():
    # L'époque fractionnaire est tout l'objet du format : tronquée à l'entier par l'analyseur
    # d'avant, elle valait 0 pendant toute la première époque et l'interface masquait alors la
    # progression, le compteur et la loss.
    event = payload(spectra_events.format_event("progress", epoch=0.97, loss=1.2345))

    assert event == {"type": "progress", "epoch": 0.97, "loss": 1.2345}


def test_absent_values_are_omitted_not_nulled():
    # Une absence se dit par l'absence de clé : un `null` obligerait le consommateur à le
    # distinguer de zéro, qui est une valeur de loss parfaitement légitime.
    event = payload(spectra_events.format_event("progress", epoch=1.0, loss=None, evalLoss=0.95))

    assert "loss" not in event
    assert event["evalLoss"] == 0.95


def test_zero_is_a_value_and_survives():
    event = payload(spectra_events.format_event("progress", epoch=0.0, loss=0.0))

    assert event["epoch"] == 0.0
    assert event["loss"] == 0.0


def test_log_declares_its_level():
    # Le niveau vient de l'émetteur, qui le connaît, au lieu d'être deviné sur des mots-clés —
    # heuristique qui laissait passer « Traceback (most recent call last): » pour une info.
    event = payload(spectra_events.format_event("log", level="ERROR", message="dataset vide"))

    assert event["level"] == "ERROR"
    assert event["message"] == "dataset vide"


def test_message_with_quotes_and_newlines_stays_one_parsable_line():
    # Le transport est ligne à ligne : un message multiligne casserait le découpage s'il n'était
    # pas échappé. JSON s'en charge — encore faut-il ne pas produire la ligne à la main.
    line = spectra_events.format_event(
        "log", level="ERROR", message='chemin "brisé"\nsur deux lignes')

    assert "\n" not in line
    assert payload(line)["message"] == 'chemin "brisé"\nsur deux lignes'


def test_accents_are_not_escaped():
    # La ligne est aussi ce qu'un exploitant lit dans train.log : « \\u00e9 » y serait illisible.
    line = spectra_events.format_event("log", level="WARN", message="entraînement dégradé")

    assert "entraînement dégradé" in line


def test_progress_helper_rounds_without_losing_the_signal():
    line = spectra_events.format_event(
        "progress", epoch=round(0.3333333333, 4), loss=round(1.23456789, 6))
    event = payload(line)

    assert event["epoch"] == 0.3333
    assert event["loss"] == 1.234568


def test_emit_writes_one_flushed_line(capsys):
    spectra_events.progress(0.5, loss=1.5)

    out = capsys.readouterr().out.splitlines()
    assert len(out) == 1
    assert payload(out[0]) == {"type": "progress", "epoch": 0.5, "loss": 1.5}


def test_log_helper_uppercases_the_level(capsys):
    spectra_events.log("error", "boum")

    assert payload(capsys.readouterr().out.strip())["level"] == "ERROR"


@pytest.mark.parametrize("bad", ["", "   ", "pas de préfixe"])
def test_a_line_without_the_sentinel_is_not_an_event(bad):
    # Le préfixe est le SEUL signal : son absence doit rester une ligne ordinaire, sans quoi un
    # train.sh plus ancien — qui n'émet que de la prose — cesserait d'être compris.
    assert not bad.startswith(spectra_events.SENTINEL)
