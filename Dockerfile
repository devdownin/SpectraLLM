# ── Build stage ──
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ── Runtime stage ──
FROM eclipse-temurin:25-jre
WORKDIR /app

# Create a non-root user
RUN groupadd -r spectra && useradd -r -g spectra spectra
RUN mkdir -p /app/data && chown -R spectra:spectra /app/data

# Install llmfit
RUN apt-get update && apt-get install -y --no-install-recommends curl wget && \
    curl -fsSL https://llmfit.axjns.dev/install.sh | sh && \
    apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=spectra:spectra /app/target/*.jar app.jar

# Use the non-root user
USER spectra

ENV JAVA_OPTS="-Xms256m -Xmx2048m -XX:+UseZGC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
