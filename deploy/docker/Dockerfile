# syntax=docker/dockerfile:1
#
# NB : les caches de build ci-dessous exigent BuildKit, actif par défaut depuis Docker 23 et
# systématique via `docker compose`. Une construction forcée sur l'ancien moteur
# (DOCKER_BUILDKIT=0) échouerait sur la syntaxe `RUN --mount`.

# ── Build stage ──
# ── Images de base épinglées par empreinte ───────────────────────────────────
# Les images TIERCES de la pile sont déjà épinglées (llama.cpp server-b9828, chromadb 1.5.9,
# kafka 4.2.0) ; celles depuis lesquelles ce projet CONSTRUIT ne l'étaient pas. Or un tag
# comme « python:3.11-slim » est réassigné toutes les semaines : deux images construites à
# quinze jours d'écart ne sont pas la même image, et rien ne le signale — exactement le
# défaut que l'épinglage de llama.cpp corrige par ailleurs (cf. scripts/llama_cpp_convert.py).
#
# L'empreinte désigne l'INDEX multi-architecture, pas un manifeste mono-arch : amd64 et arm64
# (Apple Silicon) restent tous deux construits depuis la même référence.
#
# Rafraîchir une empreinte :
#   docker buildx imagetools inspect python:3.11-slim --format '{{.Manifest.Digest}}'
FROM maven:3-eclipse-temurin-25@sha256:1b1fc6d0168ea616afd1c861d6f32ec37c9ec2ffe88a0351b3771dd4ad86b0d8 AS build
WORKDIR /app
COPY backend/pom.xml .

# Cache PERSISTANT du dépôt Maven local, partagé entre les constructions.
#
# Sans lui, la moindre modification du pom.xml invalide la couche et fait retélécharger
# l'intégralité des dépendances depuis Maven Central — plusieurs centaines de mégaoctets.
# Ce n'est pas qu'une question de durée : c'est le point le plus exposé au réseau de toute
# la pile, et il a déjà fait tomber la CI sur un simple « 403 Forbidden » de Maven Central,
# sans aucun rapport avec le code soumis.
#
# Le cache vit HORS des couches de l'image : la taille finale est inchangée.
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

COPY backend/src ./src
# Même cache : la compilation réutilise les artefacts déjà résolus ci-dessus. Sans le
# partager, cette étape les retéléchargerait tous une seconde fois.
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -B

# ── Runtime stage ──
FROM eclipse-temurin:25-jre@sha256:f19dbf0a22d0b3658fda48ce7d7181df05ad14bda151dd5ad12cc09d1451c70e
WORKDIR /app

# Create a non-root user
RUN groupadd -r spectra && useradd -r -g spectra spectra
RUN mkdir -p /app/data && chown -R spectra:spectra /app/data

# ── Installation de llmfit ───────────────────────────────────────────────────
# L'installateur était PIPÉ dans un shell (`curl … | sh`). Deux défauts distincts :
#
# 1. Le shell exécute pendant que le téléchargement se poursuit. Une connexion coupée en
#    cours de transfert fait donc exécuter un script TRONQUÉ, à moitié appliqué, sans que
#    rien ne le signale — l'image se construit, et l'anomalie n'apparaît qu'à l'usage.
# 2. Rien ne vérifie ce qui est exécuté. Le dépôt applique pourtant déjà ce principe au
#    convertisseur GGUF (cf. scripts/llama_cpp_convert.py, constat F12) : « deux exports
#    faits à quelques semaines d'intervalle n'utilisaient pas le même code, et rien ne le
#    signalait ». Le raisonnement vaut mot pour mot ici.
#
# On télécharge donc D'ABORD dans un fichier — curl échoue alors franchement sur un
# transfert incomplet — puis on vérifie, puis on exécute.
#
# LLMFIT_SHA256 : somme de contrôle attendue de l'installateur. Vide par défaut, faute de
# valeur publiée en amont ; la renseigner rend la construction reproductible et vérifiée.
# Pour l'obtenir une fois : curl -fsSL https://llmfit.axjns.dev/install.sh | sha256sum
ARG LLMFIT_SHA256=""
ARG LLMFIT_INSTALL_URL="https://llmfit.axjns.dev/install.sh"
RUN apt-get update && apt-get install -y --no-install-recommends curl wget \
    && curl -fsSL --retry 3 --retry-delay 2 "$LLMFIT_INSTALL_URL" -o /tmp/llmfit-install.sh \
    && test -s /tmp/llmfit-install.sh \
    && if [ -n "$LLMFIT_SHA256" ]; then \
         echo "$LLMFIT_SHA256  /tmp/llmfit-install.sh" | sha256sum -c -; \
       else \
         echo "AVERTISSEMENT: LLMFIT_SHA256 non renseignée — l'installateur llmfit est exécuté" \
              "sans vérification d'intégrité. Renseignez-la (--build-arg LLMFIT_SHA256=…) pour" \
              "rendre cette construction reproductible. Empreinte du fichier obtenu :" \
              "$(sha256sum /tmp/llmfit-install.sh | cut -d' ' -f1)"; \
       fi \
    && sh /tmp/llmfit-install.sh \
    && rm -f /tmp/llmfit-install.sh \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=spectra:spectra /app/target/*.jar app.jar

# Use the non-root user
USER spectra

ENV JAVA_OPTS="-Xms256m -Xmx2048m -XX:+UseZGC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
