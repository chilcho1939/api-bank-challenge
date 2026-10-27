# syntax=docker/dockerfile:1

# ---------- 1. Compilación ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Primero solo lo que define dependencias: si el pom no cambia, esta capa se reutiliza.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw

COPY src src
# La caché de ~/.m2 sobrevive entre builds: no se descargan las dependencias cada vez.
# Los tests se corren fuera (necesitan Docker para Testcontainers).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q package -DskipTests \
    && cp target/*.jar application.jar

# ---------- 2. Extracción de capas ----------
FROM eclipse-temurin:21-jdk AS layers
WORKDIR /workspace
COPY --from=build /workspace/application.jar .
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------- 3. Ejecución ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /application

# Sin privilegios de root dentro del contenedor.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

# De lo que menos cambia a lo que más cambia: un cambio de código solo invalida la última capa.
COPY --from=layers /workspace/extracted/dependencies/ ./
COPY --from=layers /workspace/extracted/spring-boot-loader/ ./
COPY --from=layers /workspace/extracted/snapshot-dependencies/ ./
COPY --from=layers /workspace/extracted/application/ ./

EXPOSE 8080

# La JVM respeta el límite de memoria del contenedor; 75% deja margen para memoria no-heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]