# ---------- build stage ----------
# Compiles the jar INSIDE the image, so `docker compose build` alone always produces
# an up-to-date artifact — no separate `mvn package` step to forget. Dependencies are
# resolved in their own layer (cached unless pom.xml changes), so a code-only change
# only re-runs the package step.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# Best-effort dependency prefetch for layer caching; never fail the build on it
# (dependency:go-offline is famously incomplete — package below fetches the rest).
RUN mvn -B -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------- runtime stage ----------
# Must stay on a glibc base (Ubuntu/Temurin). The tree-sitter native libraries bundled
# by io.github.bonede are *-linux-gnu-*.so and have no musl build, so an -alpine base
# would fail with UnsatisfiedLinkError at parse time.
FROM eclipse-temurin:17-jdk

# git is required by the deterministic code-extraction toolkit (git clone).
# graphviz provides the `dot` binary the dependency-graph visualization shells out to,
# to render the graph as a PNG attachment (Phase 2 static image).
RUN apt-get update \
    && apt-get install -y --no-install-recommends git graphviz \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENV TZ=Asia/Taipei

COPY --from=build /build/target/ChatOps4Msa-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
