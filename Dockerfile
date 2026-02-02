# -------------------------------------------------
# BUILD STAGE – Maven 3.9.9 + JDK 17 (TLS-safe)
# -------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B clean package -DskipTests

# -------------------------------------------------
# RUNTIME STAGE – Tiny JDK 17 JRE (Alpine)
# -------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=build /app/target/moniewise-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]
