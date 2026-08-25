# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing source does not re-download
# the world on every rebuild.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: they need a Docker daemon (Testcontainers),
# which is not available inside an image build. `./mvnw verify` on the host is the
# gate; this stage only packages what that gate already approved.
RUN mvn -B -q package -DskipTests

# Split the jar so Docker caches dependencies separately from our own classes: a
# code-only change then ships a few hundred KB instead of ~60 MB. The extractor
# writes a jar whose Main-Class is the application and whose manifest Class-Path
# points at a sibling lib/ directory.
RUN java -Djarmode=tools -jar target/meta-autoreply-*.jar extract --layers --destination extracted \
 && mv extracted/application/meta-autoreply-*.jar extracted/application/app.jar

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root: a webhook endpoint is internet-facing by definition.
RUN addgroup -S app && adduser -S -G app app

# Only these two layers are copied. `snapshot-dependencies` and
# `spring-boot-loader` come out empty for this project — there are no SNAPSHOT
# dependencies, and the tools extractor produces a directly executable jar rather
# than one needing the Boot launcher — and COPY of an empty directory fails the
# build. Add them back if a SNAPSHOT dependency is ever introduced.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app
EXPOSE 8080

# Readiness rather than plain liveness: it reports UP only once the context has
# refreshed and the datasource is reachable, which is what "ready for traffic"
# actually means. Meta will retry a webhook that arrives before then, but there is
# no reason to make it.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

# MaxRAMPercentage so the JVM sizes itself from the container limit rather than
# the host's memory.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
