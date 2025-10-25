FROM openjdk:17

WORKDIR /app
ENV TZ=Asia/Taipei

COPY target/ChatOps4Msa-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]