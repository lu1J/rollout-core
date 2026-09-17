FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 rolloutcore && useradd --uid 10001 --gid rolloutcore --no-create-home rolloutcore
WORKDIR /app
ARG APP_MODULE=rolloutcore-server
COPY --from=build /build/${APP_MODULE}/target/${APP_MODULE}-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
