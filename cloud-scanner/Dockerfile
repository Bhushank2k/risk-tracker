FROM gradle:8.13-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :cloud-scanner:installDist --no-daemon

FROM eclipse-temurin:17-jre
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
WORKDIR /app
COPY --from=build /workspace/cloud-scanner/build/install/cloud-scanner /app
USER 10001
ENTRYPOINT ["/app/bin/cloud-scanner"]
