# syntax=docker/dockerfile:1
# -------------------------------------------------
# BUILD STAGE
# -------------------------------------------------
# The build needs a GitHub Packages token to resolve com.moniewise:monnie-sdk-*.
# Two ways in, so this works on any host:
#
#   BuildKit secret (preferred - never recorded in image metadata):
#     docker build --secret id=GH_PACKAGES_TOKEN,src=./gh_token .
#     On Render, add a secret file named GH_PACKAGES_TOKEN; Render exposes secret
#     files to docker build as BuildKit secrets under the same id.
#
#   Build arg (fallback, for hosts that only offer build-time env vars):
#     docker build --build-arg GH_PACKAGES_TOKEN=ghp_xxx .
#
# Either way the token stays in this stage. The runtime stage copies only the jar.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

ARG GH_PACKAGES_USER=x-access-token
ARG GH_PACKAGES_TOKEN=

COPY pom.xml .
COPY .mvn/settings.xml .mvn/settings.xml

# -U overrides the pom's <updatePolicy>never</updatePolicy>, which exists so a
# developer without a token is not blocked by a 401. Here we always want the newest
# SDK snapshot, which is the whole point of publishing on every push to main.
RUN --mount=type=secret,id=GH_PACKAGES_TOKEN,dst=/run/secrets/GH_PACKAGES_TOKEN \
    sh -c 'export GH_PACKAGES_USER="$GH_PACKAGES_USER"; \
           export GH_PACKAGES_TOKEN="${GH_PACKAGES_TOKEN:-$(cat /run/secrets/GH_PACKAGES_TOKEN 2>/dev/null)}"; \
           mvn -B -ntp -s .mvn/settings.xml -U dependency:go-offline'

COPY src ./src

RUN --mount=type=secret,id=GH_PACKAGES_TOKEN,dst=/run/secrets/GH_PACKAGES_TOKEN \
    sh -c 'export GH_PACKAGES_USER="$GH_PACKAGES_USER"; \
           export GH_PACKAGES_TOKEN="${GH_PACKAGES_TOKEN:-$(cat /run/secrets/GH_PACKAGES_TOKEN 2>/dev/null)}"; \
           mvn -B -ntp -s .mvn/settings.xml -U clean package -Dmaven.test.skip=true'

# -------------------------------------------------
# RUNTIME STAGE
# -------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Ensure this matches your actual JAR name from pom.xml
COPY --from=build /app/target/moniewise-backend-0.0.1-SNAPSHOT.jar app.jar

# Render injects a $PORT environment variable automatically.
# We tell Spring Boot to listen to whatever Render provides.
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]
