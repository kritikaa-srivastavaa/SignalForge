FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY .mvn .mvn
COPY src src
# PostgreSQL/Kafka integration tests run separately with Maven verify.
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine-3.23
WORKDIR /app
# Alpine provides BusyBox wget; verify it is available for the healthcheck.
RUN wget --help > /dev/null 2>&1
COPY --from=build /build/target/signalforge-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
