# syntax=docker/dockerfile:1
# Image de base épinglée par empreinte (index multi-arch : amd64 et arm64). Un tag comme
# « python:3.11-slim » est réassigné toutes les semaines. Justification complète et
# commande de rafraîchissement : deploy/docker/Dockerfile.
FROM python:3.11-slim@sha256:90744cff8f32887f075c47d747a173ff333e9e98801667af93c357fa9f5e28ff

WORKDIR /app

# Install dependencies first (cache layer)
COPY requirements.txt .
# Cache pip hors des couches : la taille de l'image est inchangée, mais une
# reconstruction ne retélécharge plus les dépendances déjà résolues.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements.txt

# Copy application
COPY app.py .

ARG RERANKER_MODEL=cross-encoder/ms-marco-MiniLM-L-6-v2
ENV RERANKER_MODEL=${RERANKER_MODEL}

# Pré-téléchargement du modèle : une OPTIMISATION (démarrage instantané), pas une exigence.
# En faire une étape bloquante rendait l'image inconstructible dès que le Hub HuggingFace était
# injoignable — proxy d'entreprise, machine hors ligne, coupure — alors même que le modèle est
# déjà présent dans le volume `reranker-model-cache` monté à l'exécution. Le build échouait donc
# faute de pouvoir RE-télécharger ce que l'installation possédait déjà.
#
# Best-effort désormais : si le téléchargement échoue, l'image se construit quand même et le
# modèle est chargé au démarrage depuis le cache monté. Contrepartie assumée : sans cache et sans
# réseau, le service échoue au démarrage — avec une traceback Python lisible, au lieu d'une
# erreur BuildKit opaque à mi-build.
RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('${RERANKER_MODEL}')" \
    || echo "AVERTISSEMENT: pré-téléchargement de '${RERANKER_MODEL}' impossible au build. \
Le modèle sera chargé au premier démarrage depuis /root/.cache/huggingface (volume \
reranker-model-cache). Vérifiez ce volume, ou HF_ENDPOINT/HF_HUB_OFFLINE, si le service ne \
démarre pas."

EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
