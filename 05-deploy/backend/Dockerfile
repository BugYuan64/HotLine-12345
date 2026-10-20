# 后端镜像：JRE17 运行 Spring Boot jar（jar 由 build.ps1 从 02-backend 复制而来）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar /app/app.jar
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
