# ── Build stage ──
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ── Runtime stage ──
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create a non-root user
RUN groupadd -r spectra && useradd -r -g spectra spectra

# Install llmfit
RUN apt-get update && apt-get install -y --no-install-recommends curl wget && \
    curl -fsSL https://llmfit.axjns.dev/install.sh | sh && \
    apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=spectra:spectra /app/target/*.jar app.jar

# Use the non-root user
USER spectra

ENV JAVA_OPTS="-Xms256m -Xmx1024m -XX:+UseZGC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
