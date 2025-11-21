# Build stage
FROM gradle:8.5-jdk21 AS builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/map-to-img.jar ./map-to-img.jar

VOLUME /world

ENTRYPOINT ["java", "-jar", "map-to-img.jar", "/world"]
