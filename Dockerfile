# syntax=docker/dockerfile:1
#
# One image recipe for all three apps (ADR 0019). Pick the app with the MODULE build argument:
#   docker build --build-arg MODULE=daybook-api -t daybook-api .
# compose.yaml does this for every app.
#
# Stage 1 compiles inside Docker, so building needs only Docker — not a local JDK or Maven.
# Tests are not run here: CI runs the full suite (they need Docker themselves, via Testcontainers).

FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src

# Poms first: the dependency download below is cached until a pom changes, not on every code edit.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY daybook-api/pom.xml daybook-api/
COPY daybook-consumer/pom.xml daybook-consumer/
COPY daybook-psp-sim/pom.xml daybook-psp-sim/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q -pl ${MODULE} -am dependency:go-offline

COPY daybook-api/src daybook-api/src
COPY daybook-consumer/src daybook-consumer/src
COPY daybook-psp-sim/src daybook-psp-sim/src
# The API's tests read the consumer's migrations, but tests are skipped, so only MODULE matters.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q -pl ${MODULE} -am package -DskipTests -Dspotless.check.skip=true \
 && cp ${MODULE}/target/${MODULE}-*.jar /src/application.jar \
 && java -Djarmode=tools -jar /src/application.jar extract --layers --destination /src/extracted

# Stage 2: only a Java runtime and the app. No compiler, no Maven, no source code.
FROM eclipse-temurin:21-jre
# curl for the container health check.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
# Never run as root inside the container.
RUN useradd --system --uid 10001 --no-create-home daybook
WORKDIR /app

# Spring Boot layers, least-often-changed first: a code change rebuilds only the last layer.
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./

USER daybook
# UTC like every other environment; heap sized from the container's memory limit, not the host's.
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "application.jar"]
