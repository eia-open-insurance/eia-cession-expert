FROM eclipse-temurin:22-jre

WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
