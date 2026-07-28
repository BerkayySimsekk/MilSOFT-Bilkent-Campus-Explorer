# syntax=docker/dockerfile:1

FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean package

FROM eclipse-temurin:21-jre

RUN groupadd --system app \
    && useradd --system --gid app app \
    && mkdir -p /app/data \
    && chown -R app:app /app

WORKDIR /app

COPY --chown=app:app --from=build /workspace/target/*.jar /app/app.jar

USER app

ENV APP_CUSTOM_LOCATIONS_FILE=/app/data/custom.geojson

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
