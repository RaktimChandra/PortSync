# syntax=docker/dockerfile:1.7
# One Dockerfile for every Java module: docker build --build-arg MODULE=portsync-core .
# (MODULE = portsync-core | simulator | loadtest)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY portsync-events/pom.xml portsync-events/
COPY simulator/pom.xml simulator/
COPY portsync-core/pom.xml portsync-core/
COPY loadtest/pom.xml loadtest/
COPY portsync-events/src portsync-events/src
COPY simulator/src simulator/src
COPY portsync-core/src portsync-core/src
COPY loadtest/src loadtest/src
ARG MODULE=portsync-core
# The Maven cache mount makes rebuilds fast; sh avoids depending on the executable bit (Windows checkouts).
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -q -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/${MODULE}.jar /app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
# Size the heap from the container's memory limit instead of the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
