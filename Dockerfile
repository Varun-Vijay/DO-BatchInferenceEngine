# App Platform has no Java buildpack, so the image is built here rather than inferred.

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change skips the download.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Tests run in CI against the real toolchain; repeating them here only slows deploys.
RUN ./mvnw -B -ntp -DskipTests package \
    && mv target/batch-inference-engine-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build --chown=appuser:appuser /build/app.jar app.jar
USER appuser

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM reads the container limit, so resizing the
# instance in App Platform resizes the heap without touching this file.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# Shell form so $JAVA_OPTS expands; exec so the JVM is PID 1 and receives SIGTERM,
# which is what triggers the dispatcher's graceful drain on a rolling deploy.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
