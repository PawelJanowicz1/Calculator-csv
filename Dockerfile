# -----------------------
# 1. Stage: build with Maven
# -----------------------
FROM maven:3.8.7-eclipse-temurin-17 AS build

# Ustaw katalog roboczy wewnątrz kontenera
WORKDIR /app

# Skopiuj pliki pom.xml i źródła
COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV SERVER_PORT=8080

# Punkt wejścia
ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar"]