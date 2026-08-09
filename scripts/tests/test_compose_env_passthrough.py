"""
Une variable lue par le backend doit franchir la frontière du conteneur.

Compose n'injecte PAS `.env` dans les conteneurs : il ne s'en sert que pour interpoler le
fichier Compose lui-même. Une variable absente du bloc `environment:` d'un service n'atteint
donc jamais le processus qui tourne dedans — et comme les lectures Spring ont toutes une
valeur par défaut (`@Value("${X:}")`), l'absence ne provoque aucune erreur : le code prend
son défaut et continue.

C'est exactement ce qui est arrivé à `SPECTRA_API_KEY`. `ApiKeyFilter` la lit et protège
`/api/**` dès qu'elle est non vide, `.env.example` explique sur dix lignes comment la
renseigner, le manuel utilisateur et l'audit de sécurité la documentent — mais
`docker-compose.yml` ne la transmettait pas. La clé restait dans `.env`, le filtre restait
sur une chaîne vide, journalisait « authentification API désactivée » et laissait passer
toutes les requêtes, pendant que `scripts/pipeline.sh` envoyait un en-tête `X-API-Key` que
personne ne vérifiait. Un contrôle de sécurité documenté à trois endroits, inatteignable par
le chemin documenté, et parfaitement silencieux.

**Pourquoi interroger la configuration RENDUE et non le YAML source.** Un `grep` sur
`docker-compose.yml` verrait la ligne `SPECTRA_API_KEY=${SPECTRA_API_KEY:-}` sans savoir
dans quel service elle vit, ni si les profils et les overlays la conservent. `docker compose
config` donne ce que Compose instanciera réellement — la seule chose qui compte ici.

**Ce que ce test ne couvre pas.** Il ne connaît que les placeholders de style variable
d'environnement (`@Value("${SCREAMING_SNAKE}")`). Les propriétés Spring pointées
(`spectra.reranker.enabled`, alimentées depuis `application.yml` et surchargeables par
liaison relâchée) relèvent d'un autre mécanisme et ne sont pas vérifiées ici.
"""

import json
import re
import shutil
import subprocess

import pytest

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
COMPOSE_FILE = "deploy/docker/docker-compose.yml"

# Le service qui exécute le backend Java, donc celui qui doit recevoir ces variables.
API_SERVICE = "spectra-api"

# Placeholder @Value de style VARIABLE D'ENVIRONNEMENT : majuscules, chiffres et
# soulignés. Écarte volontairement les propriétés Spring pointées (`${spectra.x.y}`).
ENV_STYLE_VALUE = re.compile(r'@Value\("\$\{([A-Z][A-Z0-9_]*)\s*[:}]')


def compose_config():
    """Configuration Compose rendue, tous profils actifs, en JSON.

    Tous les profils : sans eux, Compose n'instancie pas les services optionnels et la
    vérification passerait à côté d'eux sans le dire.

    `--format json` plutôt que YAML : le job CI n'installe que pytest, et json est dans la
    bibliothèque standard. Ajouter PyYAML pour lire une sortie que Compose sait déjà émettre
    en JSON ferait dépendre ce contrôle d'une installation supplémentaire.
    """
    proc = subprocess.run(
        ["docker", "compose", "--project-directory", ".", "-f", COMPOSE_FILE,
         "config", "--format", "json"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={"PATH": "/usr/bin:/bin:/usr/local/bin",
             "COMPOSE_PROFILES": "layout-parser,reranker,kafka,trainer",
             "HOME": str(Path.home())},
    )
    assert proc.returncode == 0, f"docker compose config a échoué :\n{proc.stderr}"
    return json.loads(proc.stdout)


def backend_env_placeholders():
    """Variables d'environnement que le code Java lit via @Value."""
    found = set()
    for java in (ROOT / "backend/src/main/java").rglob("*.java"):
        found |= set(ENV_STYLE_VALUE.findall(java.read_text(encoding="utf-8", errors="replace")))
    return found


# Un contrôle sauté est signalé COMME TEL, jamais compté comme réussi — même règle que
# scripts/verify.sh. Un vert obtenu en n'exécutant rien est pire que pas de vert du tout.
needs_compose = pytest.mark.skipif(
    shutil.which("docker") is None
    or subprocess.run(["docker", "compose", "version"],
                      capture_output=True).returncode != 0,
    reason="docker compose indisponible",
)


def test_backend_reads_at_least_one_env_placeholder():
    """Sans cette garde, le test ci-dessous passerait à vide si la regex cessait de matcher."""
    placeholders = backend_env_placeholders()
    assert placeholders, (
        "aucun placeholder @Value de style variable d'environnement trouvé dans le backend — "
        "la regex ne correspond plus au code, et le contrôle suivant ne vérifie plus rien."
    )


@needs_compose
def test_env_placeholders_reach_the_api_container():
    placeholders = backend_env_placeholders()
    environment = compose_config()["services"][API_SERVICE].get("environment", {})

    missing = sorted(p for p in placeholders if p not in environment)

    assert not missing, (
        f"{missing} : lue(s) par le backend, mais absente(s) de l'environnement rendu du "
        f"service {API_SERVICE}. Compose n'injecte pas .env dans les conteneurs — une "
        "variable absente du bloc environment: n'atteint jamais la JVM, qui prendra sa "
        "valeur par défaut sans rien signaler. Ajoutez "
        "« - NOM=${NOM:-} » au service."
    )


@needs_compose
def test_published_ports_bind_to_loopback_by_default():
    """
    Le défaut d'exposition est une décision, pas un accident.

    Les ports étaient publiés en « 8000:8000 », donc sur 0.0.0.0 : tout le réseau local
    atteignait ChromaDB en lecture ET en écriture — l'index vectoriel, donc le corpus
    ingéré — ainsi que les serveurs llama.cpp, qui n'ont aucune authentification. Rien de
    tout cela n'est nécessaire au fonctionnement : les services se parlent par le réseau
    Compose. Ce test empêche un retour silencieux à 0.0.0.0 lors d'un ajout de service.

    SPECTRA_BIND_ADDR reste là pour un déploiement multi-hôte volontaire ; c'est le DÉFAUT
    qui est vérifié ici, pas l'impossibilité d'ouvrir.
    """
    exposed = []
    for name, service in compose_config()["services"].items():
        for port in service.get("ports", []):
            host_ip = port.get("host_ip")
            if host_ip != "127.0.0.1":
                # Compose omet host_ip quand aucune adresse n'est donnée — c'est le cas
                # « 8081:8081 », le plus exposé, et celui qui doit se lire sans ambiguïté.
                where = host_ip or "toutes interfaces (0.0.0.0)"
                exposed.append(f"{name} → {where}, port {port.get('published')}")

    assert not exposed, (
        "port(s) publié(s) hors de la boucle locale par défaut : "
        + ", ".join(exposed)
        + ". Utilisez « ${SPECTRA_BIND_ADDR:-127.0.0.1}:HÔTE:CONTENEUR »."
    )
