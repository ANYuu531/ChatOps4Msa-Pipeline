FROM eclipse-temurin:17-jdk

# git is required by the deterministic code-extraction toolkit (git clone).
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENV TZ=Asia/Taipei

COPY target/ChatOps4Msa-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]