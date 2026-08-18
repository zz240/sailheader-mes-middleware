# 阶段 1：前端构建（Vite + Vue3）
FROM node:22-alpine AS frontend
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci || npm install
COPY frontend/ .
RUN npm run build

# 阶段 2：后端构建（把前端产物拷进静态目录后打包）
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY spike/pom.xml .
COPY spike/src ./src
COPY --from=frontend /app/dist ./src/main/resources/static
RUN mvn -q package -DskipTests

# 阶段 3：运行（单镜像，同源交付）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
