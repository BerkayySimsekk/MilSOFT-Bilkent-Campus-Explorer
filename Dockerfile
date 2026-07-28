FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/bilkent-campus-explorer-0.0.1-SNAPSHOT.jar app.jar

ENV APP_CUSTOM_LOCATIONS_FILE=/app/data/custom.geojson

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]