FROM mcr.microsoft.com/devcontainers/java:1-25-bullseye

# Install Maven
ARG MAVEN_VERSION=3.9.6
RUN su vscode -c "umask 0002 && . /usr/local/sdkman/bin/sdkman-init.sh && sdk install maven \"${MAVEN_VERSION}\""

# Install additional tools if needed
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends curl wget gnupg2

# [Optional] Install Docker CLI if you want to use Docker from within the container
# RUN apt-get update && apt-get -y install docker.io
