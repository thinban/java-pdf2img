## 使用Maven镜像作为构建环境
#FROM maven:3.8.3-openjdk-17 AS build
#WORKDIR /app
#COPY pom.xml .
#COPY src ./src
#RUN mvn package

## 使用OpenJDK镜像作为运行环境
FROM khipu/openjdk17-alpine
WORKDIR /app
#COPY --from=build /app/target/java-pdf2img.jar ./app.jar
COPY target/java-pdf2img.jar ./app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
