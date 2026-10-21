# syntax=docker/dockerfile:1
# Apache Jena Fuseki 6.2.0 whose TDB2 datasets infer RDFS classes, with stored-only reads.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
COPY templates ./templates
# Tests run in CI (mvn verify) before any image is built or published.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM secoresearch/fuseki:6.2.0@sha256:74833bbf3967f41f5f98ddd6b861abf1a426705056da3bbdb516b8a703342aa9
USER root
COPY --from=build /src/target/linked-fuseki-module.jar /javalibs/linked-fuseki-module.jar
COPY templates/config-tdb2 /fuseki-base/templates/config-tdb2
COPY configuration/assembler.ttl /fuseki-base/configuration/assembler.ttl
# The image runs as uid 9008, group 0; the entrypoint edits assembler.ttl in place (ENABLE_*).
RUN chown -R 9008:0 /fuseki-base/templates /fuseki-base/configuration \
 && chmod -R g+rwX /fuseki-base/templates /fuseki-base/configuration
USER 9008
LABEL org.opencontainers.image.source="https://github.com/linked-fw/fuseki" \
      org.opencontainers.image.description="Apache Jena Fuseki 6.2.0 whose datasets infer RDFS classes, with stored-only reads" \
      org.opencontainers.image.licenses="Apache-2.0"
