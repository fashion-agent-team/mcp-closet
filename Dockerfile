# 빌드: docker build -t ghcr.io/fashion-agent-team/mcp-closet:dev .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Seoul
# 임베딩 모델(multilingual-e5-small, 양자화 ONNX 약 118MB)을 이미지에 넣어 뜰 때 내려받지 않게 한다.
ADD --chmod=644 https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx /app/models/model.onnx
ADD --chmod=644 https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json /app/models/tokenizer.json
ENV CLOSET_MODEL_DIR=/app/models \
    DJL_CACHE_DIR=/tmp/djl \
    DJL_OFFLINE=true
COPY --from=build /src/build/libs/mcp-closet-*.jar app.jar
EXPOSE 8095
USER 1000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
