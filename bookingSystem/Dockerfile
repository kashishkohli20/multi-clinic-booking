# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 booking && useradd --uid 10001 --gid booking --no-create-home booking
WORKDIR /app
COPY --from=build --chown=booking:booking /workspace/build/libs/booking-service.jar app.jar
USER booking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
