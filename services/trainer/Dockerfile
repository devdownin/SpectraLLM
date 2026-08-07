# Spectra Trainer — fine-tuning QLoRA dans son propre conteneur.
#
# Contexte de build : la RACINE du dépôt (le service a besoin de scripts/, hors de son
# propre répertoire). Cf. deploy/docker/docker-compose.yml, service « trainer ».
# Le .dockerignore de la racine réduit ce contexte de ~360 Mo à ~4 Mo.

# ── Étape 1 : compilation des dépendances ────────────────────────────────────
# build-essential et git ne servent QU'À installer : certaines dépendances d'entraînement
# compilent des extensions natives, d'autres se récupèrent depuis un dépôt Git. Les garder
# dans l'image servie ajouterait ~300 Mo à la plus lourde de la pile, pour du code qui ne
# s'exécutera jamais.
FROM python:3.11-slim AS builder

RUN apt-get update \
    && apt-get install --no-install-recommends -y git build-essential \
    && rm -rf /var/lib/apt/lists/*

# Couche de dépendances d'abord : elle pèse plusieurs Go (torch) et ne doit pas être
# reconstruite à chaque modification d'un script.
COPY services/trainer/requirements.txt /tmp/api-requirements.txt
COPY scripts/requirements.txt /tmp/training-requirements.txt
RUN pip install --no-cache-dir --prefix=/install -r /tmp/api-requirements.txt \
    && pip install --no-cache-dir --prefix=/install -r /tmp/training-requirements.txt

# ── Étape 2 : image servie ───────────────────────────────────────────────────
FROM python:3.11-slim

# WORKDIR /app et volume ./data:/app/data : la symétrie avec spectra-api n'est pas cosmétique.
# Le client transmet des chemins ABSOLUS calculés dans le conteneur applicatif
# (/app/data/fine-tuning/<job>/dataset.jsonl). Un point de montage différent ici ferait
# démarrer l'entraînement puis échouer sur un fichier introuvable — exactement le mode de panne
# tardif que ce service est censé supprimer.
WORKDIR /app

# gosu : abandon de privilèges dans l'entrypoint, une fois l'UID cible connu. Seule dépendance
# système de l'image servie — tout le reste de l'outillage de build est resté à l'étape 1.
RUN apt-get update \
    && apt-get install --no-install-recommends -y gosu \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /install /usr/local

# Cache HuggingFace hors de /root : le service abandonne ses privilèges au démarrage, et un
# chemin sous /root cesserait alors d'être celui que consulte huggingface_hub. Le volume monté
# deviendrait inerte et le modèle de base — plusieurs Go — se retéléchargerait à CHAQUE
# redémarrage du conteneur, sans que rien ne le signale.
ENV HF_HOME=/hf-cache
RUN mkdir -p /hf-cache

# Sortie non tamponnée : /train diffuse la sortie du script ligne à ligne vers l'UI. Sur un tube,
# Python bufférise par blocs — tout ce qui précède la première étape d'entraînement (dont le
# téléchargement du modèle de base, plusieurs minutes) n'atteignait le flux qu'après coup.
ENV PYTHONUNBUFFERED=1

# Les scripts d'entraînement sont copiés depuis le dépôt applicatif, et non dupliqués : une
# copie divergerait, et le format de conversation (chat_format.py) est la source de vérité
# partagée avec le manifeste des modèles de base.
COPY scripts/ /app/scripts/
COPY backend/src/main/resources/base_models.json /app/backend/src/main/resources/base_models.json
COPY services/trainer/app.py /app/app.py
COPY services/trainer/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# ── Convertisseur GGUF embarqué (F12) ─────────────────────────────────────────
# L'export GGUF téléchargeait convert_hf_to_gguf.py À L'EXÉCUTION : un export échouait donc sans
# accès sortant, en contradiction avec la promesse « 100 % local · même air-gapped », et rien
# dans l'interface ne le laissait prévoir.
#
# On le récupère ici, à la CONSTRUCTION de l'image, où le réseau est disponible et où le résultat
# est figé dans une couche. La révision vient de scripts/llama_cpp_convert.py — source unique,
# alignée sur le tag de l'image llama-server qui servira le GGUF produit. Le fichier porte la
# révision dans son nom : une image plus ancienne ne fournit jamais en silence un convertisseur
# d'une autre révision que celle demandée à l'exécution.
#
# LLAMA_CPP_REVISION surcharge la révision au build (elle est aussi lue à l'exécution).
ARG LLAMA_CPP_REVISION=""
ENV LLAMA_CPP_REVISION=${LLAMA_CPP_REVISION}
RUN python3 /app/scripts/llama_cpp_convert.py /opt/llama-cpp-converters

EXPOSE 8000

# L'entrypoint aligne l'utilisateur sur le propriétaire du volume partagé avant de céder la
# main : sans cela, tout ce que le trainer écrit appartiendrait à root et spectra-api — non
# privilégié — ne pourrait plus purger les répertoires de job.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# Un seul worker, délibérément : l'entraînement sature déjà CPU/RAM, et le service tient l'état
# des processus en cours en mémoire (annulation). Plusieurs workers rendraient
# POST /cancel/{jobId} non déterministe — la requête pouvant atterrir sur le worker qui ne
# détient pas le processus.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
