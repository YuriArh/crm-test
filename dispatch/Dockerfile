# Build context: repository root (the Gradle wrapper and settings live there).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached between source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY dispatch/build.gradle.kts dispatch/
# Every project included in settings.gradle.kts needs its directory, even if it is not built here.
COPY e2e/build.gradle.kts e2e/
COPY carrier-emulator/build.gradle.kts carrier-emulator/
RUN ./gradlew --no-daemon :dispatch:dependencies > /dev/null

COPY dispatch/src dispatch/src
RUN ./gradlew --no-daemon :dispatch:bootJar -x test

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/dispatch/build/libs/dispatch.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
