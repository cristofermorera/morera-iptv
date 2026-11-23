FROM node:24-bookworm-slim AS ui
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=ui /app/dist/ ./src/main/resources/static/
RUN mvn -B -ntp package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/platform-1.0.0.jar ./nexo.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/nexo.jar"]
