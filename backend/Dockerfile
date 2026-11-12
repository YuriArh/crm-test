# Build context: repository root (the Gradle wrapper and settings live there).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached between source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN ./gradlew --no-daemon :backend:dependencies > /dev/null

COPY backend/src backend/src
RUN ./gradlew --no-daemon :backend:bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/backend.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
