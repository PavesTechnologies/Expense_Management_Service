# ---- Build stage ----
# Uses `mvn` directly, not `./mvnw` — the Maven wrapper jar is gitignored/not committed
# (only .mvn/wrapper/maven-wrapper.properties is present), so ./mvnw fails in a clean
# build context like this one.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Runtime stage ----
# Multi-arch base image — the CD pipeline (jenkins-shared-lib/vars/deployToDev.groovy)
# builds this via `docker buildx build --platform linux/arm64`, so nothing here may
# assume an amd64-only base.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Do NOT copy .env into the image — it's gitignored and (as of this writing) holds
# live-looking AWS/DB credentials in local dev. Every required env var must come from
# the Kubernetes Secret (see intranet-devops k8s/backend/xms/deployment.yaml's
# envFrom: secretRef: xms-secrets) instead.
COPY --from=build /build/target/expense-management-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
