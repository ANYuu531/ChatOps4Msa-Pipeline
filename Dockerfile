# Must stay on a glibc base (Ubuntu). The tree-sitter native libraries bundled by
# io.github.bonede are *-linux-gnu-*.so and have no musl build, so an -alpine base
# would fail with UnsatisfiedLinkError at parse time.
FROM eclipse-temurin:17-jdk

# git is required by the deterministic code-extraction toolkit (git clone).
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENV TZ=Asia/Taipei

# git is required by the dependency-analysis code extraction, which clones the
# target repository. It is not present in the base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

COPY target/ChatOps4Msa-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
