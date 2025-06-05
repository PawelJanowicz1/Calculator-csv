# -----------------------
# 1. Stage: build with Maven
# -----------------------
FROM maven:3.8.7-eclipse-temurin-17 AS build

# Ustaw katalog roboczy wewnątrz kontenera
WORKDIR /app

# Skopiuj pliki pom.xml i źródła
COPY pom.xml .
COPY src ./src

# Zbuduj aplikację Spring Boot (tworzy plik target/*.jar)
RUN mvn clean package -DskipTests

# -----------------------
# 2. Stage: uruchomienie aplikacji
# -----------------------
FROM eclipse-temurin:17-jre-alpine

# W katalogu /app w warstwie runtime będziemy trzymać gotowy JAR
WORKDIR /app

# Skopiuj JAR z etapu “build”
COPY --from=build /app/target/*.jar app.jar

# Ustaw port, na którym Spring Boot domyślnie nasłuchuje (Render poda wartość PORT jako zmienną, ale “8080” to fallback)
ENV SERVER_PORT=8080

# Punkt wejścia
ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar"]