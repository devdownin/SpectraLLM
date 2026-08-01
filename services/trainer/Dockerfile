# Spectra Trainer — fine-tuning QLoRA dans son propre conteneur.
#
# Contexte de build : la RACINE du dépôt (le service a besoin de scripts/, hors de son
# propre répertoire). Cf. deploy/docker/docker-compose.yml, service « trainer ».
FROM python:3.11-slim

# WORKDIR /app et volume ./data:/app/data : la symétrie avec spectra-api n'est pas cosmétique.
# Le client transmet des chemins ABSOLUS calculés dans le conteneur applicatif
# (/app/data/fine-tuning/<job>/dataset.jsonl). Un point de montage différent ici ferait
# démarrer l'entraînement puis échouer sur un fichier introuvable — exactement le mode de panne
# tardif que ce service est censé supprimer.
WORKDIR /app

# git : certaines dépendances d'entraînement se récupèrent depuis des dépôts Git.
# build-essential : compilation d'extensions natives (bitsandbytes et consorts).
RUN apt-get update \
    && apt-get install --no-install-recommends -y git build-essential \
    && rm -rf /var/lib/apt/lists/*

# Couche de dépendances d'abord : elle pèse plusieurs Go (torch), et ne doit pas être
# reconstruite à chaque modification d'un script.
COPY services/trainer/requirements.txt /tmp/api-requirements.txt
COPY scripts/requirements.txt /tmp/training-requirements.txt
RUN pip install --no-cache-dir -r /tmp/api-requirements.txt \
    && pip install --no-cache-dir -r /tmp/training-requirements.txt

# Les scripts d'entraînement sont copiés depuis le dépôt applicatif, et non dupliqués : une
# copie divergerait, et le format de conversation (chat_format.py) est la source de vérité
# partagée avec le manifeste des modèles de base.
COPY scripts/ /app/scripts/
COPY backend/src/main/resources/base_models.json /app/backend/src/main/resources/base_models.json
COPY services/trainer/app.py /app/app.py

EXPOSE 8000

# Un seul worker, délibérément : l'entraînement sature déjà CPU/RAM, et le service tient l'état
# des processus en cours en mémoire (annulation). Plusieurs workers rendraient
# POST /cancel/{jobId} non déterministe — la requête pouvant atterrir sur le worker qui ne
# détient pas le processus.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
