# Build stage
FROM maven:3.8.2-jdk-11 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Run stage
FROM openjdk:11-jre-slim
WORKDIR /app
COPY --from=build /app/target/moniewise-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=${PORT:8080}"]