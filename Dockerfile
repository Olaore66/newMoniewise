# -------------------------------------------------
# BUILD STAGE
# -------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# -------------------------------------------------
# RUNTIME STAGE
# -------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# Ensure this matches your actual JAR name from pom.xml
COPY --from=build /app/target/moniewise-backend-0.0.1-SNAPSHOT.jar app.jar

# Render injects a $PORT environment variable automatically.
# We tell Spring Boot to listen to whatever Render provides.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=${PORT}"]