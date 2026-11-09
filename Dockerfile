# 抖音云容器服务部署用。若平台模板指定固定端口，构建时通过 SERVER_PORT 环境变量覆盖即可。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/douyin-live-ai.jar app.jar
ENV SERVER_PORT=8080
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
