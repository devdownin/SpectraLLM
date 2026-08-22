"""
Les trois définitions des images publiées doivent rester la même définition.

La publication Docker Hub existe en trois exemplaires, et c'est irréductible : un workflow
GitHub Actions (`docker-publish.yml`) qui pousse, un script shell
(`scripts/publish-images.sh`) qui fait la même chose depuis un poste, et un overlay Compose
(`docker-compose.hub.yml`) qui tire le résultat. Trois langages, trois fichiers, une seule
vérité — le nom des images.

Une dérive ne produit aucune erreur au moment où elle est commise. Elle produit, plus tard,
l'une de ces deux pannes, toutes deux silencieuses côté auteur :

  — le script pousse `spectrallm-web` pendant que l'overlay tire `spectrallm-frontend` :
    l'utilisateur reçoit un `manifest unknown` sur une image dont il n'a jamais entendu
    parler, à un moment où il ne peut rien diagnostiquer ;
  — l'overlay référence un service qui n'existe pas dans le fichier Compose de base :
    Compose ne signale RIEN, il CRÉE le service — une image sans ports, sans volumes et
    sans healthcheck, pendant que le vrai service continue de se construire à côté.

Ce test relie les quatre fichiers. Il ne vérifie pas que la publication fonctionne — cela
demande un registre — mais que les noms, eux, ne peuvent plus diverger en silence.
"""

import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOW = REPO_ROOT / ".github/workflows/docker-publish.yml"
PUBLISH_SH = REPO_ROOT / "scripts/publish-images.sh"
HUB_OVERLAY = REPO_ROOT / "deploy/docker/docker-compose.hub.yml"
BASE_COMPOSE = REPO_ROOT / "deploy/docker/docker-compose.yml"
ENV_EXAMPLE = REPO_ROOT / ".env.example"
HUB_PAGES = REPO_ROOT / "deploy/docker/hub"
RELEASE_NOTES = REPO_ROOT / ".github/release-notes"
START_SH = REPO_ROOT / "scripts/start.sh"
START_BAT = REPO_ROOT / "scripts/start.bat"

# Limites imposées par Docker Hub sur un dépôt.
SHORT_DESCRIPTION_MAX = 100
FULL_DESCRIPTION_MAX = 25_000

# Images publiées à CHAQUE version (les autres sont derrière `--include-profiled`).
DEFAULT_IMAGES = {"spectrallm", "spectrallm-frontend"}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def workflow_matrix_entries() -> list[dict]:
    """Entrées de matrice du workflow, lues comme du JSON (elles en sont)."""
    entries = []
    for line in read(WORKFLOW).splitlines():
        stripped = line.strip().rstrip(",")
        if stripped.startswith('{"image":'):
            entries.append(json.loads(stripped))
    return entries


def script_entries() -> list[dict]:
    """Entrées du script shell : « nom|contexte|dockerfile|plateformes|build-args »."""
    entries = []
    for match in re.finditer(
        r'"(spectrallm[a-z-]*)\|([^|"]*)\|([^|"]*)\|([^|"]*)\|([^"]*)"', read(PUBLISH_SH)
    ):
        name, context, dockerfile, platforms, build_args = match.groups()
        entries.append(
            {
                "image": name,
                "context": context,
                "dockerfile": dockerfile,
                "platforms": platforms,
                "build_args": build_args,
            }
        )
    return entries


def overlay_images() -> dict[str, str]:
    """Service Compose → nom d'image (sans espace de noms ni tag), lu dans l'overlay."""
    images = {}
    service = None
    for line in read(HUB_OVERLAY).splitlines():
        if re.match(r"^  [a-z][a-z0-9_-]*:\s*$", line):
            service = line.strip().rstrip(":")
        match = re.search(r"^\s+image:\s*\$\{SPECTRA_IMAGE_NAMESPACE:-[^}]+\}/([a-z-]+):", line)
        if match and service:
            images[service] = match.group(1)
    return images


def compose_services(path: Path) -> set[str]:
    body = read(path)
    services_block = body[body.index("\nservices:"):]
    return {
        m.group(1)
        for m in re.finditer(r"^  ([a-z][a-z0-9_-]*):\s*$", services_block, re.M)
    }


def default_namespaces() -> dict[str, str]:
    """Espace de noms par défaut, tel que déclaré par chacun des fichiers concernés."""
    found = {}

    match = re.search(r'^DEFAULT_NAMESPACE="([^"]+)"', read(PUBLISH_SH), re.M)
    if match:
        found["scripts/publish-images.sh"] = match.group(1)

    overlay = re.findall(r"\$\{SPECTRA_IMAGE_NAMESPACE:-([a-z0-9_-]+)\}", read(HUB_OVERLAY))
    if overlay:
        found["docker-compose.hub.yml"] = overlay[0]
        assert len(set(overlay)) == 1, (
            f"l'overlay déclare plusieurs espaces de noms par défaut : {sorted(set(overlay))}"
        )

    match = re.search(r"^#SPECTRA_IMAGE_NAMESPACE=([a-z0-9_-]+)", read(ENV_EXAMPLE), re.M)
    if match:
        found[".env.example"] = match.group(1)

    return found


# ── Les fichiers existent et se lisent ───────────────────────────────────────────

@pytest.mark.parametrize(
    "path", [WORKFLOW, PUBLISH_SH, HUB_OVERLAY, BASE_COMPOSE, ENV_EXAMPLE]
)
def test_files_exist(path):
    assert path.exists(), f"{path.relative_to(REPO_ROOT)} manquant"


def test_both_publication_paths_declare_images():
    """
    Filet préalable : si l'un des deux parseurs ci-dessus ne trouve rien (format changé),
    toutes les comparaisons qui suivent passeraient à vide et ne prouveraient plus rien.
    """
    assert workflow_matrix_entries(), "aucune entrée de matrice lue dans docker-publish.yml"
    assert script_entries(), "aucune image lue dans scripts/publish-images.sh"
    assert overlay_images(), "aucune image lue dans docker-compose.hub.yml"


# ── Cohérence entre les deux chemins de publication ──────────────────────────────

def test_workflow_and_script_publish_the_same_images():
    workflow = {e["image"] for e in workflow_matrix_entries()}
    script = {e["image"] for e in script_entries()}

    assert workflow == script, (
        "le workflow et le script ne publient pas les mêmes images — "
        f"absentes du script : {sorted(workflow - script) or '—'} ; "
        f"absentes du workflow : {sorted(script - workflow) or '—'}"
    )


def test_workflow_and_script_agree_on_context_and_dockerfile():
    """
    Deux chemins qui publient sous le MÊME nom depuis des sources différentes produisent
    deux images différentes sous un tag unique. La panne apparaît chez l'utilisateur, et
    le tag ne dit pas laquelle des deux il a reçue.
    """
    script = {e["image"]: e for e in script_entries()}

    for entry in workflow_matrix_entries():
        other = script[entry["image"]]
        assert entry["dockerfile"] == other["dockerfile"], (
            f"{entry['image']} : Dockerfile différent entre workflow "
            f"({entry['dockerfile']}) et script ({other['dockerfile']})"
        )
        # « . » et « frontend » côté workflow, mêmes valeurs côté script : le contexte
        # décide de ce qui entre dans l'image, un écart y est aussi grave qu'un Dockerfile
        # différent.
        assert entry["context"] == other["context"], (
            f"{entry['image']} : contexte de construction différent entre workflow "
            f"({entry['context']}) et script ({other['context']})"
        )


def test_every_published_image_builds_from_an_existing_dockerfile():
    for entry in workflow_matrix_entries():
        dockerfile = REPO_ROOT / entry["dockerfile"]
        context = REPO_ROOT / entry["context"]
        assert dockerfile.is_file(), f"{entry['image']} : {entry['dockerfile']} introuvable"
        assert context.is_dir(), f"{entry['image']} : contexte {entry['context']} introuvable"


# ── Cohérence entre publication et consommation ──────────────────────────────────

def test_overlay_only_pulls_images_published_by_default():
    """
    Les services profilés ne sont publiés que sur demande (`include_profiled`). Un overlay
    qui les épinglerait ferait échouer au tirage tout utilisateur d'un profil sur une
    version où ils n'ont pas été publiés — là où la construction locale, elle, fonctionne.
    """
    pulled = set(overlay_images().values())

    assert pulled <= DEFAULT_IMAGES, (
        f"l'overlay tire des images non publiées par défaut : {sorted(pulled - DEFAULT_IMAGES)}. "
        "Publiez-les à chaque version, ou retirez-les de l'overlay."
    )


def test_default_images_are_actually_published_by_default():
    """Filet inverse : le workflow doit publier, sans drapeau, ce que l'overlay tire."""
    published = {e["image"] for e in workflow_matrix_entries()}

    assert DEFAULT_IMAGES <= published, (
        f"image(s) attendue(s) à chaque publication mais absente(s) du workflow : "
        f"{sorted(DEFAULT_IMAGES - published)}"
    )


def test_overlay_services_exist_in_the_base_compose_file():
    """
    Un nom de service erroné n'est pas signalé par Compose : il CRÉE le service. On
    obtiendrait une image tirée mais sans ports, sans volumes ni healthcheck, pendant que
    le vrai service continue de se construire à côté — deux backends, aucun message.
    """
    base = compose_services(BASE_COMPOSE)
    unknown = set(overlay_images()) - base

    assert not unknown, (
        f"service(s) de l'overlay absent(s) de docker-compose.yml : {sorted(unknown)}. "
        "Compose les créerait au lieu de surcharger le service visé."
    )


def test_the_default_namespace_is_declared_identically_everywhere():
    """
    Trois fichiers portent une valeur par défaut. Si elles divergent, l'utilisateur qui ne
    renseigne rien tire depuis un espace de noms que personne ne publie — et le message
    (`denied` ou `manifest unknown`) ne désigne jamais le fichier fautif.
    """
    found = default_namespaces()

    assert len(found) == 3, (
        f"espace de noms par défaut introuvable dans : "
        f"{sorted({'scripts/publish-images.sh', 'docker-compose.hub.yml', '.env.example'} - set(found))}"
    )
    assert len(set(found.values())) == 1, f"espaces de noms par défaut divergents : {found}"


# ── Les deux chemins posent les mêmes tags ───────────────────────────────────────

# Expressions GitHub Actions à neutraliser pour rejouer l'étape hors d'Actions.
WORKFLOW_EXPRESSIONS = {
    "${{ github.event_name }}": "$EVENT_NAME",
    "${{ github.ref_name }}": "$REF_NAME",
}


def workflow_resolve_script() -> str:
    """
    Corps shell de l'étape « Resolve » du workflow, extrait sans PyYAML.

    Le job `training-scripts` de la CI n'installe que pytest : ajouter une dépendance pour
    lire une indentation ferait dépendre ce contrôle d'une installation supplémentaire —
    et un contrôle qu'on n'installe pas est un contrôle qui ne tourne pas.
    """
    body = read(WORKFLOW)
    run_at = body.index("run: |", body.index("id: resolve"))
    indent = len(body[:run_at].rsplit("\n", 1)[1])
    script, block = [], body[run_at:].splitlines()[1:]
    for line in block:
        if line.strip() and not line.startswith(" " * (indent + 2)):
            break
        script.append(line[indent + 2:])
    return "\n".join(script)


def workflow_tags(tmp_path, ref_name: str) -> list[str]:
    script = workflow_resolve_script()
    for expression, replacement in WORKFLOW_EXPRESSIONS.items():
        script = script.replace(expression, replacement)

    assert "${{" not in script, (
        "expression GitHub Actions non neutralisée dans l'étape « Resolve » : "
        "complétez WORKFLOW_EXPRESSIONS, sinon bash l'interpréterait comme du texte."
    )

    path = tmp_path / "resolve.sh"
    path.write_text(script, encoding="utf-8")
    output = tmp_path / "github_output"
    output.touch()

    proc = subprocess.run(
        ["bash", str(path)],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        env={
            "PATH": "/usr/bin:/bin:/usr/local/bin",
            "HOME": str(Path.home()),
            "EVENT_NAME": "push",
            "REF_NAME": ref_name,
            "INPUT_VERSION": "",
            "DRY_RUN": "false",
            "INCLUDE_PROFILED": "false",
            "DESCRIPTIONS_ONLY": "false",
            "HUB_USERNAME": "acme",
            "HUB_NAMESPACE": "",
            "HAS_TOKEN": "true",
            "GITHUB_OUTPUT": str(output),
            "GITHUB_STEP_SUMMARY": str(tmp_path / "summary"),
        },
    )
    assert proc.returncode == 0, f"l'étape « Resolve » a échoué :\n{proc.stderr}"

    line = re.search(r"^tags=(.*)$", output.read_text(encoding="utf-8"), re.M)
    assert line, "l'étape « Resolve » n'a produit aucune sortie « tags »"
    return line.group(1).split(",")


def script_tags(ref_name: str) -> list[str]:
    proc = subprocess.run(
        ["bash", "scripts/publish-images.sh", "--print-plan", "--tag", ref_name],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    assert proc.returncode == 0, f"publish-images.sh a échoué :\n{proc.stderr}"

    line = re.search(r"^\s*tags\s+:\s*(.+)$", proc.stdout, re.M)
    assert line, f"aucun plan lisible dans la sortie de publish-images.sh :\n{proc.stdout}"
    return line.group(1).split()


@pytest.mark.parametrize(
    "ref_name, expected",
    [
        # La série (« 0.7 ») donne les correctifs sans la montée de version.
        ("v0.7.1", ["0.7.1", "0.7", "latest"]),
        # « 0.7 » ne se redonne pas lui-même — et surtout pas sous la forme « 0 ».
        ("v0.7", ["0.7", "latest"]),
        # Une release candidate ne doit pas devenir le défaut de qui n'épingle rien.
        ("v1.0.0-rc1", ["1.0.0-rc1"]),
    ],
)
def test_both_publication_paths_produce_the_same_tags(tmp_path, ref_name, expected):
    """
    Les règles de tag vivent en double : dans le workflow et dans le script. Une divergence
    ne casse rien au moment où elle est commise — elle publie, plus tard, un `latest` que
    l'autre chemin n'aurait pas posé, ou un tag « 0 » qu'aucun utilisateur n'attendait.

    On EXÉCUTE donc les deux, plutôt que de comparer deux textes qui se ressemblent.
    """
    if not shutil.which("bash"):  # pragma: no cover - environnement sans bash
        pytest.skip("bash indisponible")

    assert workflow_tags(tmp_path, ref_name) == expected
    assert script_tags(ref_name) == expected


def test_the_namespace_never_travels_through_a_job_output():
    """
    Actions REFUSE de propager une sortie de job dont la valeur est celle d'un secret.

    Ce n'est pas une hypothèse : la première publication a échoué exactement là-dessus.
    L'espace de noms venait de `secrets.DOCKERHUB_USERNAME`, le journal a écrit « Skip
    output 'namespace' since it may contain secret », la valeur est arrivée VIDE dans les
    jobs de build, et buildx a rejeté « invalid tag "/spectrallm-frontend:edge-…" ».

    Le préflight ne l'exporte donc plus : chaque job de publication le lit lui-même depuis
    `env:`, où le masquage n'existe qu'à l'affichage. Remettre cette sortie remettrait la
    panne — d'où ce test, qui la rendrait bruyante au lieu de la laisser revenir.
    """
    body = read(WORKFLOW)
    outputs_block = body[body.index("    outputs:"):body.index("    steps:")]

    assert "namespace:" not in outputs_block, (
        "le job preflight exporte à nouveau une sortie « namespace » : Actions la videra "
        "dès qu'elle vaudra un secret, et les jobs de publication bâtiront un tag « /image:tag »."
    )
    assert "NAMESPACE: ${{ vars.DOCKERHUB_NAMESPACE || secrets.DOCKERHUB_USERNAME }}" in body, (
        "le job de publication ne résout plus l'espace de noms lui-même depuis vars/secrets"
    )


# ── Pages de présentation Docker Hub ─────────────────────────────────────────────

def hub_pages() -> dict[str, Path]:
    """Nom d'image → page de présentation. Le nom de fichier EST le nom du dépôt."""
    return {path.stem: path for path in sorted(HUB_PAGES.glob("*.md"))}


def test_every_image_published_by_default_has_a_hub_page():
    """
    Un dépôt Docker Hub sans description longue affiche « No overview available » sous un
    nom d'image. C'est la première chose que voit quelqu'un qui découvre le projet par le
    registre — et elle ne répond à aucune des deux questions qu'il se pose.
    """
    missing = DEFAULT_IMAGES - set(hub_pages())

    assert not missing, (
        f"image(s) publiée(s) sans page de présentation : {sorted(missing)}. "
        f"Ajoutez deploy/docker/hub/<image>.md."
    )


def test_every_hub_page_matches_a_published_image():
    """
    Le job « describe » dérive le nom du dépôt du NOM DE FICHIER. Une page dont le nom ne
    correspond à aucune image publiée viserait un dépôt inexistant : la publication
    échouerait sur un 404 dont la cause — une faute de frappe dans un nom de fichier — ne
    se lit nulle part.
    """
    published = {e["image"] for e in workflow_matrix_entries()}
    # La matrice par défaut n'a pas les images profilées ; elles restent publiables.
    profiled = {f"spectrallm-{s}" for s in ("docparser", "reranker", "trainer")}
    orphans = set(hub_pages()) - published - profiled

    assert not orphans, (
        f"page(s) de présentation sans image correspondante : {sorted(orphans)}"
    )


@pytest.mark.parametrize("image", sorted(hub_pages()))
def test_each_hub_page_declares_a_short_description(image):
    """La description courte est lue DANS la page, en commentaire HTML : deux textes dans
    deux fichiers divergeraient, et c'est le plus visible des deux qui deviendrait faux."""
    body = read(hub_pages()[image])
    match = re.search(r"^<!-- short: (.+) -->$", body, re.M)

    assert match, f"{image}.md ne déclare pas de « <!-- short: … --> »"
    assert len(match.group(1)) <= SHORT_DESCRIPTION_MAX, (
        f"{image}.md : description courte de {len(match.group(1))} caractères, "
        f"Docker Hub en accepte {SHORT_DESCRIPTION_MAX}"
    )


@pytest.mark.parametrize("image", sorted(hub_pages()))
def test_each_hub_page_fits_the_docker_hub_limit(image):
    size = len(read(hub_pages()[image]))

    assert size <= FULL_DESCRIPTION_MAX, (
        f"{image}.md fait {size} caractères ; Docker Hub tronque au-delà de "
        f"{FULL_DESCRIPTION_MAX}"
    )


@pytest.mark.parametrize("image", sorted(hub_pages()))
def test_hub_pages_link_and_embed_absolutely(image):
    """
    Docker Hub ne résout AUCUN chemin relatif : un `docs/assets/x.png` copié depuis le
    README y donne une image cassée, et un lien mort. Le défaut ne se voit pas au moment
    où on l'écrit — la page s'affiche parfaitement dans l'éditeur et dans GitHub — mais
    seulement sur la vitrine publique du projet.
    """
    body = read(hub_pages()[image])
    targets = re.findall(r"\]\(([^)]+)\)", body) + re.findall(r'(?:src|href)="([^"]+)"', body)
    relative = [t for t in targets if not t.startswith(("http://", "https://", "#"))]

    assert not relative, (
        f"{image}.md contient {len(relative)} lien(s)/image(s) relatif(s), cassé(s) sur "
        f"Docker Hub : {relative[:5]}"
    )


# ── Version tirée par défaut ─────────────────────────────────────────────────────

def overlay_default_tags() -> list[str]:
    """Valeur(s) par défaut de SPECTRA_IMAGE_TAG dans l'overlay."""
    return re.findall(r"\$\{SPECTRA_IMAGE_TAG:-([^}]+)\}", read(HUB_OVERLAY))


def latest_release_tag() -> str | None:
    """
    Dernier tag de version du dépôt (`vX.Y[.Z]`), ou None si les tags sont absents.

    LE TAG GIT, ET RIEN D'AUTRE. C'est lui qui décide du tag d'image : `docker-publish.yml`
    se déclenche sur `v*` et en dérive `X.Y.Z`, `X.Y` et `latest`. Toute autre source
    répondrait à une question différente de celle qu'on pose.

    Ce contrôle a d'abord été accroché à `.github/release-notes/`, en supposant que ce
    répertoire suivait les tags. v0.8.0 a prouvé le contraire : le tag existait, le fichier
    non — et le contrôle aurait affirmé que la dernière version était la 0.7.1. D'où
    `test_the_latest_release_tag_has_curated_notes`, qui rend cet écart bruyant.

    `actions/checkout` ne récupère pas les tags par défaut ; le job qui exécute ces tests
    demande donc `fetch-depth: 0`. Sans tags — clone superficiel, archive téléchargée — le
    contrôle est SAUTÉ et le dit : un contrôle qui conclut sur rien est pire qu'un contrôle
    absent.
    """
    proc = subprocess.run(
        ["git", "tag", "--list", "v*", "--sort=-v:refname"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return None
    for line in proc.stdout.splitlines():
        if re.fullmatch(r"v\d+(?:\.\d+)*", line.strip()):
            return line.strip()
    return None


def latest_released_version() -> str | None:
    """Le dernier tag, sans son « v » — la forme que portent les tags d'image."""
    tag = latest_release_tag()
    return tag[1:] if tag else None


def test_both_services_pull_the_same_version():
    """
    Le backend et le frontend sont bâtis depuis le même commit et ne sont jamais testés
    séparément. Deux défauts différents dans l'overlay assembleraient deux versions qui
    n'ont jamais tourné ensemble — et rien ne le signalerait au démarrage.
    """
    defaults = overlay_default_tags()

    assert defaults, "aucun défaut SPECTRA_IMAGE_TAG lu dans l'overlay"
    assert len(set(defaults)) == 1, (
        f"l'overlay tire des versions différentes selon le service : {defaults}"
    )


def test_the_overlay_default_is_latest_or_the_released_version():
    """
    Le défaut de l'overlay décide de ce que reçoit quiconque ne configure rien.

    Deux valeurs sont acceptables, et une seule à la fois :

      — « latest », état explicitement transitoire tant qu'aucune version n'a été publiée,
        ou choix assumé d'une vitrine accueillante ;
      — la dernière version publiée, épinglée — le raisonnement que ce dépôt applique déjà
        à llama.cpp et ChromaDB, avec un précédent concret : le tag mobile de ChromaDB a
        déplacé son chemin de persistance sans un message, et l'index vectoriel repartait
        de zéro à chaque « docker compose down ».

    Ce qui n'est PAS acceptable, c'est une troisième valeur : un numéro figé qui n'est plus
    celui de la dernière version. Il ne casse rien — il sert simplement une version périmée
    à tous ceux qui n'épinglent pas, sans que personne s'en aperçoive.
    """
    default = overlay_default_tags()[0]

    if default == "latest":
        return

    released = latest_released_version()
    if released is None:
        pytest.skip("tags git absents (clone superficiel) — impossible de vérifier l'épinglage")

    assert default == released, (
        f"l'overlay tire « {default} » par défaut alors que le dernier tag publié est "
        f"« v{released} ». Après chaque publication, alignez le défaut de "
        "deploy/docker/docker-compose.hub.yml — sinon les utilisateurs qui n'épinglent rien "
        "restent sur une version périmée, en silence."
    )


def test_the_latest_release_tag_has_curated_notes():
    """
    `release.yml` charge `.github/release-notes/<tag>.md` pour le corps de la release. Sans
    ce fichier, il publiait la liste brute des PR fusionnées — dependabot compris. C'est
    arrivé à la v0.7, puis à la v0.8.0 : quatre-vingts lignes sur la page que voit quiconque
    clique « Releases ».

    Le workflow échoue désormais quand le fichier manque. Ce test le dit AVANT le tag, au
    moment où la correction ne coûte qu'un fichier — plutôt qu'après, quand la release est
    déjà publiée et qu'il faut l'éditer à la main.
    """
    tag = latest_release_tag()
    if tag is None:
        pytest.skip("tags git absents (clone superficiel)")

    notes = RELEASE_NOTES / f"{tag}.md"

    assert notes.is_file(), (
        f"le dernier tag {tag} n'a pas de notes curées ({notes.relative_to(REPO_ROOT)}). "
        "Écrivez-les — sinon la release part avec la liste brute des PR fusionnées."
    )


def test_the_hub_option_is_wired_and_not_merely_announced():
    """
    `--hub` promet de démarrer sans rien construire. Trois choses doivent être vraies pour
    que la promesse tienne, et aucune ne se voit dans la ligne d'usage :

      — l'overlay doit être CHARGÉ (sans lui, Compose construit comme avant, en silence) ;
      — `--no-build` doit être passé à `up` : c'est ce qui transforme une image absente en
        échec franc, au lieu d'une reconstruction qui annule tout l'intérêt du tirage ;
      — les images doivent être TIRÉES avant le démarrage.

    Le test de parité Windows compare les options ANNONCÉES ; il ne dit rien de ce
    qu'elles font. C'est exactement l'écart qui a laissé `stop.bat` ne pas arrêter les
    services profilés — même interface, comportements différents.
    """
    for path, overlay in ((START_SH, "SPECTRA_COMPOSE_HUB_FILE"), (START_BAT, "docker-compose.hub.yml")):
        body = read(path)
        name = path.name

        assert "--hub" in body, f"{name} n'accepte pas --hub"
        assert overlay in body, f"{name} ne charge pas l'overlay des images publiées"
        assert "--no-build" in body, (
            f"{name} ne passe pas --no-build : une image absente serait reconstruite en "
            "silence, et le mode --hub deviendrait un no-op invisible"
        )
        assert "pull spectra-api frontend" in body, (
            f"{name} ne tire pas les deux images de l'overlay avant de démarrer"
        )


def test_the_documentation_names_the_secrets_the_workflow_reads():
    """
    Le workflow échoue si les secrets manquent — c'est voulu. Encore faut-il que le document
    qu'il désigne dise LESQUELS créer, sous leur nom exact.
    """
    doc = read(REPO_ROOT / "docs/tech/docker-hub.fr.md")
    workflow = read(WORKFLOW)

    for secret in re.findall(r"secrets\.([A-Z_]+)", workflow):
        if secret == "GITHUB_TOKEN":  # fourni par Actions, rien à créer
            continue
        assert secret in doc, (
            f"le workflow lit secrets.{secret}, que docs/tech/docker-hub.fr.md ne documente pas"
        )
