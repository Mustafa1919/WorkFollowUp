# --- Build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

COPY src/ src/
# Spring Boot 4 "layertools" jarmode'unu kaldirdi; yerine "tools" + "extract --layers" geldi. Bu
# modda katmanlanmis cikti "application.jar" adiyla calistirilir (Class-Path manifest'ten gelir).
RUN ./mvnw package -DskipTests -B \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Run stage ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS run
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Ayrı katmanlar: bağımlılıklar (nadiren değişir) kod katmanından (her build'de değişir) ayrılır.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
