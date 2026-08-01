#!/bin/sh
# Aligne l'utilisateur du trainer sur celui qui possède le volume partagé, puis démarre l'API.
#
# Le problème
# -----------
# `spectra-api` tourne sous un compte non privilégié (`USER spectra`), le trainer sous root.
# Les deux partagent `./data:/app/data`. Tout ce que le trainer y écrit — adaptateur LoRA,
# modèle fusionné, sorties intermédiaires — appartient donc à root, et `spectra-api` ne peut
# plus le supprimer : la purge des répertoires de job échoue en silence, et le disque se
# remplit sans que rien ne le signale.
#
# Pourquoi pas un UID codé en dur
# --------------------------------
# `spectra-api` crée son compte avec `useradd -r`, qui prend le premier UID système libre —
# une valeur non déterministe, susceptible de changer avec la base d'image. Figer un UID ici
# supposerait de figer le sien, ce qui ferait perdre à toute installation existante la
# propriété des fichiers déjà présents dans `data/`. Casser les installations en place pour
# corriger un défaut de propriété serait un mauvais échange.
#
# On lit donc l'appartenance réelle du volume et on s'y aligne. Le trainer suit `spectra-api`
# quel que soit son UID, aujourd'hui et après une mise à jour de sa base d'image.
set -eu

DATA_DIR="${TRAINER_DATA_DIR:-/app/data}"

if [ ! -d "$DATA_DIR" ]; then
    echo "[entrypoint] $DATA_DIR absent — démarrage en root sans alignement." >&2
    exec "$@"
fi

TARGET_UID="$(stat -c %u "$DATA_DIR")"
TARGET_GID="$(stat -c %g "$DATA_DIR")"

# Volume possédé par root : soit la pile entière tourne en root, soit le volume vient d'être
# créé par Docker et personne n'a encore écrit. Rien à aligner.
if [ "$TARGET_UID" = "0" ]; then
    echo "[entrypoint] $DATA_DIR appartient à root — aucun alignement nécessaire."
    exec "$@"
fi

# -o : autorise un GID/UID déjà attribué dans l'image de base. Sans lui, la création échoue
# quand la valeur cible coïncide avec un compte système existant, et le service démarrerait
# en root — c'est-à-dire précisément le défaut qu'on corrige, mais silencieusement.
if ! getent group "$TARGET_GID" >/dev/null 2>&1; then
    groupadd -o -g "$TARGET_GID" trainer
fi
if ! getent passwd "$TARGET_UID" >/dev/null 2>&1; then
    useradd -o -u "$TARGET_UID" -g "$TARGET_GID" -M -d /app -s /sbin/nologin trainer
fi

# Le cache HuggingFace est un volume nommé, créé par Docker et donc possédé par root : sans
# ce chown, le téléchargement du modèle de base échouerait dès le premier entraînement.
HF_CACHE="${HF_HOME:-/hf-cache}"
if [ -d "$HF_CACHE" ]; then
    chown -R "$TARGET_UID:$TARGET_GID" "$HF_CACHE" || \
        echo "[entrypoint] Cache HuggingFace non réattribué ($HF_CACHE) — téléchargements possiblement en échec." >&2
fi

echo "[entrypoint] Alignement sur le propriétaire de $DATA_DIR (uid=$TARGET_UID gid=$TARGET_GID)."
exec gosu "$TARGET_UID:$TARGET_GID" "$@"
