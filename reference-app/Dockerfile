FROM node:22-alpine AS web
WORKDIR /src/frontend
COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-17 AS java
WORKDIR /src/backend
COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=web /src/frontend/dist ./src/main/resources/static
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=java /src/backend/target/campus-market-1.0.0.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 18080
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","app.jar"]
