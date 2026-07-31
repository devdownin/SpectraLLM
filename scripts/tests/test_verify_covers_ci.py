"""
`scripts/verify.sh` doit rejouer TOUS les contrôles de la CI.

Le script annonce « rejoue les contrôles de l'intégration continue ». Il a été livré en
ignorant silencieusement deux d'entre eux — les suites pytest de `services/docparser` et
`services/reranker` — c'est-à-dire en commettant exactement le défaut contre lequel il a été
écrit : un vert obtenu en n'exécutant pas tout.

Ce test relie les deux fichiers. Ajouter un job à `ci.yml` sans lui donner de contrepartie
locale fait désormais échouer la CI, avec le nom du job manquant.

Les jobs qui n'ont volontairement pas d'équivalent local sont déclarés ci-dessous **avec
leur raison**. Une exemption sans motif n'en est pas une : c'est un oubli déguisé.
"""

import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CI_WORKFLOW = REPO_ROOT / ".github/workflows/ci.yml"
VERIFY_SH = REPO_ROOT / "scripts/verify.sh"

# Job CI → section de verify.sh qui le rejoue.
JOB_TO_SECTION = {
    "build": "backend",
    "static-analysis": "spotbugs",
    "model-defaults": "models",
    "training-scripts": "python",
    "python-services": "services",
    "frontend": "frontend",
}

# Jobs sans contrepartie locale, et pourquoi.
EXEMPT_JOBS = {
    "e2e": "lève la stack Docker complète et télécharge les modèles (plusieurs Go) : "
           "hors de portée d'une vérification avant commit",
    "codecov": "publie la couverture vers un service externe ; ne vérifie rien en soi",
}


def ci_jobs():
    """Noms des jobs de ci.yml, lus sans dépendance YAML (pytest seul en CI)."""
    body = CI_WORKFLOW.read_text(encoding="utf-8")
    jobs_block = body[body.index("\njobs:"):]
    return {m.group(1) for m in re.finditer(r"^  ([a-z][a-z0-9-]*):$", jobs_block, re.M)}


def verify_sections():
    """Sections déclarées par verify.sh, lues dans son tableau SECTIONS."""
    body = VERIFY_SH.read_text(encoding="utf-8")
    declared = re.search(r"^SECTIONS=\(([^)]*)\)", body, re.M).group(1)
    return set(declared.split())


def test_every_ci_job_is_replayed_or_explicitly_exempt():
    missing = {
        job for job in ci_jobs()
        if job not in JOB_TO_SECTION and job not in EXEMPT_JOBS
    }

    assert not missing, (
        f"job(s) CI sans contrepartie dans scripts/verify.sh : {sorted(missing)}. "
        "Ajoutez-y une section, ou déclarez le job dans EXEMPT_JOBS avec sa raison."
    )


def test_every_mapped_section_actually_exists():
    """Une correspondance vers une section inexistante donnerait une fausse couverture."""
    sections = verify_sections()
    unknown = {s for s in JOB_TO_SECTION.values() if s not in sections}

    assert not unknown, (
        f"section(s) référencée(s) par la table mais absente(s) de verify.sh : {sorted(unknown)}"
    )


@pytest.mark.parametrize("section", sorted(set(JOB_TO_SECTION.values())))
def test_each_mapped_section_is_implemented_not_just_declared(section):
    """
    Déclarer une section dans SECTIONS sans écrire le bloc correspondant produirait un
    contrôle qui ne fait rien — et un récapitulatif vide qu'on lirait comme un succès.
    """
    body = VERIFY_SH.read_text(encoding="utf-8")

    assert re.search(rf"wanted {re.escape(section)}\b", body), (
        f"la section « {section} » est déclarée mais aucun bloc `wanted {section}` "
        "ne l'exécute"
    )


def test_exemptions_carry_a_reason():
    """Une exemption sans motif est un oubli déguisé."""
    for job, reason in EXEMPT_JOBS.items():
        assert reason and len(reason) > 20, f"exemption non motivée pour « {job} »"


def test_the_currently_known_jobs_are_all_accounted_for():
    """
    Filet inverse : si un job disparaît de ci.yml, la table le référence encore et la
    couverture annoncée devient fausse.
    """
    jobs = ci_jobs()
    stale = {job for job in {**JOB_TO_SECTION, **EXEMPT_JOBS} if job not in jobs}

    assert not stale, (
        f"job(s) référencé(s) par ce test mais absent(s) de ci.yml : {sorted(stale)}. "
        "Retirez-les de JOB_TO_SECTION / EXEMPT_JOBS."
    )
