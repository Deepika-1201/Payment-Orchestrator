# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && \
    java -Djarmode=tools -jar build/libs/payment-gateway-*.jar extract --layers --launcher --destination build/extracted

FROM eclipse-temurin:25-jre
# The base image's Pebble service manager is never run here; dropping it keeps its Go runtime out of the image (ADR-033).
RUN rm -f /usr/bin/pebble && groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
# CA bundle for sslmode=verify-full against Aurora/RDS (ADR-026).
ADD --chmod=644 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /app/certs/rds-global-bundle.pem
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER app
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
