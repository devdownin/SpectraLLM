"""
Un téléchargement interrompu ne doit jamais passer pour un succès.

Les modèles par défaut pèsent ~4,8 Go. Sur une connexion domestique, une coupure en cours
de transfert n'est pas un cas limite : c'est le cas courant. Or les deux scripts de setup
écrivaient **directement sur le nom définitif** :

    curl -L --fail --progress-bar "$URL" -o data/models/embed.gguf

Une coupure, un Ctrl-C ou un disque plein laissaient donc un GGUF tronqué à l'emplacement
attendu. Et le lancement suivant ne teste que la PRÉSENCE du fichier :

    [OK] Qwen2.5-7B-Instruct-Q4_K_M.gguf présent — 2,1G

Le script était satisfait, la pile démarrait, et la panne se manifestait ailleurs :
`llama-server` refusant le modèle, ou l'entrypoint attendant un fichier qui *existe*.
Silencieuse au moment où elle est commise, diagnostiquée très loin de sa cause.

`setup.bat` ajoutait sa propre variante : il lui manquait `--fail`, que `setup.sh` avait.
Sans ce drapeau, une 404 ou une page d'erreur HTML est enregistrée comme si c'était le
modèle, curl sort en 0, et le script annonce « [OK] telecharge ». Un GGUF de 12 Ko
contenant du HTML — sur Windows uniquement, ce que la comparaison des options annoncées
(`test_windows_scripts_parity`) ne pouvait pas voir.

Les deux scripts passent désormais par un sous-programme unique. Ce test verrouille ses
quatre propriétés, parce qu'aucune ne se voit à la relecture d'un appel isolé.
"""

import re
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent.parent
SETUP_SH = SCRIPTS / "setup.sh"
SETUP_BAT = SCRIPTS / "setup.bat"


def curl_invocations(path: Path) -> list[str]:
    """Lignes qui APPELLENT curl — commentaires et messages d'aide écartés."""
    lines = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or "curl" not in line:
            continue
        if line.startswith(("#", "::", "rem ", "REM ", "echo")):
            continue
        # « echo "    curl -L … " » : de l'aide affichée, pas un téléchargement.
        if re.match(r'^echo\b', line, re.I):
            continue
        lines.append(line)
    return lines


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_every_download_goes_through_the_single_helper(path):
    """
    Un seul appel à curl par script, celui du sous-programme. Un second appel serait un
    téléchargement qui contourne le garde-fou — et il n'y a aucune raison d'en écrire un :
    le sous-programme prend une URL et une destination.
    """
    invocations = curl_invocations(path)

    assert len(invocations) == 1, (
        f"{path.name} contient {len(invocations)} appel(s) à curl au lieu d'un seul "
        f"(celui du sous-programme de téléchargement) : {invocations}"
    )


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_the_download_never_writes_to_the_final_name(path):
    """
    Le fichier définitif ne doit exister que s'il est COMPLET : on télécharge vers
    « .part », et on ne renomme qu'au succès.
    """
    invocation = curl_invocations(path)[0]

    assert "PART" in invocation.upper(), (
        f"{path.name} : curl n'écrit pas vers un fichier « .part » — un transfert "
        f"interrompu laisserait un modèle tronqué sous son nom définitif.\n  {invocation}"
    )

    body = path.read_text(encoding="utf-8", errors="replace")
    renames = ("mv -f", "move /y")
    assert any(r in body for r in renames), (
        f"{path.name} : rien ne renomme le « .part » vers le nom définitif"
    )


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_the_download_fails_loudly_on_an_http_error(path):
    """
    Sans `--fail`, curl enregistre la page d'erreur du serveur et sort en 0 : le script
    annonce un succès sur un fichier qui n'est pas le modèle. C'est ce qui manquait au
    jumeau Windows.
    """
    invocation = curl_invocations(path)[0]

    assert "--fail" in invocation, (
        f"{path.name} : curl sans --fail — une 404 serait enregistrée comme si c'était le "
        f"modèle.\n  {invocation}"
    )


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_the_gguf_downloads_declare_their_expected_format(path):
    """
    Un miroir mal configuré, un proxy d'entreprise ou un portail captif renvoient une page
    en **200** : `--fail` la laisse passer, et le « modèle » obtenu n'est rejeté qu'au
    démarrage de llama.cpp, plusieurs étapes plus loin. Quatre octets suffisent à le savoir
    tout de suite — encore faut-il que les appels les réclament.
    """
    body = path.read_text(encoding="utf-8", errors="replace")
    calls = re.findall(r"fetch_file .*", body)
    gguf_calls = [c for c in calls if "gguf" in c.lower() and "GGUF" in c]

    assert len(gguf_calls) == 2, (
        f"{path.name} : {len(gguf_calls)} téléchargement(s) GGUF déclarent le format attendu, "
        f"deux étaient prévus (embedding et chat) : {calls}"
    )


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_verification_happens_before_the_rename(path):
    """
    L'ordre est tout : vérifier APRÈS le renommage laisserait, le temps d'un contrôle, un
    fichier invalide sous le nom que toute la chaîne considère comme valide — et sur un
    échec, il faudrait le supprimer, c'est-à-dire réintroduire exactement l'état qu'on
    voulait rendre impossible.
    """
    body = path.read_text(encoding="utf-8", errors="replace")

    if path.suffix == ".sh":
        # Corps de la fonction, et non le fichier entier : un commentaire parlant
        # d'empreinte ailleurs suffirait sinon à faire passer ce test pour de mauvaises
        # raisons.
        start = body.index("fetch_file() {")
        block = body[start:body.index("\n}", start)]
        checks = [block.index(m) for m in ('"$magic"', "file_sha256") if m in block]
        rename_at = block.index("mv -f")
    else:
        # « ^:verify_file » et non « :verify_file » : la première occurrence est l'APPEL,
        # à l'intérieur même du bloc qu'on veut découper.
        start = re.search(r"^:fetch_file$", body, re.M).start()
        end = re.search(r"^:verify_file$", body, re.M).start()
        block = body[start:end]
        checks = [block.index("call :verify_file")] if "call :verify_file" in block else []
        rename_at = block.index("move /y")

    assert checks, f"{path.name} : aucune vérification dans le corps du téléchargement"
    assert max(checks) < rename_at, (
        f"{path.name} : le renommage vers le nom définitif précède la vérification — "
        "un fichier invalide porterait ce nom, ne serait-ce qu'un instant, et il faudrait "
        "l'effacer ensuite : exactement l'état qu'on veut rendre impossible."
    )


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_the_model_source_is_redirectable(path):
    """
    Réseau d'entreprise, miroir HuggingFace, installation cloisonnée : la source doit être
    déplaçable sans éditer le script. HF_ENDPOINT est la variable que le projet utilise
    DÉJÀ pour cela (docker-compose.yml la transmet au reranker et au trainer) — en inventer
    une seconde pour le même besoin obligerait à déclarer le miroir à deux endroits.
    """
    body = path.read_text(encoding="utf-8", errors="replace")

    assert "HF_ENDPOINT" in body, f"{path.name} : le miroir HuggingFace n'est pas redirigeable"
    for var in ("SPECTRA_EMBED_MODEL_URL", "SPECTRA_CHAT_MODEL_URL"):
        assert var in body, f"{path.name} : {var} absente (source arbitraire impossible)"


@pytest.mark.parametrize("path", [SETUP_SH, SETUP_BAT])
def test_the_download_resumes_instead_of_restarting(path):
    """
    4,7 Go coupés à 90 % : sans reprise, on retélécharge les 90 % déjà obtenus. `-C -` est
    passé inconditionnellement — curl démarre à zéro si le « .part » est absent, et ne fait
    rien s'il est déjà complet.
    """
    invocation = curl_invocations(path)[0]

    assert "-C -" in invocation, (
        f"{path.name} : curl sans « -C - » — une coupure ferait tout recommencer.\n"
        f"  {invocation}"
    )
    assert "--retry" in invocation, (
        f"{path.name} : curl sans --retry — une erreur transitoire échoue du premier coup.\n"
        f"  {invocation}"
    )
