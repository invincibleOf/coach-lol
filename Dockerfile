# --- Etapa 1: build (compila dentro del contenedor; no necesitas Maven en tu host) ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cacheamos dependencias: si el pom no cambia, Docker reutiliza esta capa.
COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q clean package -DskipTests

# --- Etapa 2: runtime (imagen ligera, solo el JRE + el jar) ---
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
