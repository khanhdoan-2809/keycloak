FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package


FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /build/target/demo-api-0.0.1-SNAPSHOT.jar app.jar

USER 10001:10001

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]