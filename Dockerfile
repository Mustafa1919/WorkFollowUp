# --- Build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

COPY src/ src/
RUN ./mvnw package -DskipTests -B \
    && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# --- Run stage ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS run
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Ayrı katmanlar: bağımlılıklar (nadiren değişir) kod katmanından (her build'de değişir) ayrılır.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER app
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
