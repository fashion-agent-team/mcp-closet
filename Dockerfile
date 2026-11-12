# 빌드: docker build -t ghcr.io/fashion-agent-team/mcp-closet:dev .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# FashionSigLIP 글 모델(closet_search imageQuery용). Hugging Face의 fp32 ONNX(441MB)를 받아 int8(~111MB)로 줄인다.
# 줄이는 방법은 tools/image-labels/quantize_text.py와 같다(정답률 fp32 0.739, int8 0.733).
FROM python:3.12-slim AS text-model
ARG FSIGLIP_BASE_URL=https://huggingface.co/Marqo/marqo-fashionSigLIP/resolve/main
RUN pip install --no-cache-dir onnxruntime==1.29.0 onnx==1.23.2
ADD --checksum=sha256:c1d501b23bddf27ba828c037b0780e44fdf47ca4c0b925ef190ab5bcf7aaf6e6 \
    ${FSIGLIP_BASE_URL}/onnx/text_model.onnx /m/text_model.onnx
ADD --checksum=sha256:5bc9b16db0e7adf5c9a2bd57f8f9c8e6ebdaabe75679cc956d007f78036d5b4a \
    ${FSIGLIP_BASE_URL}/tokenizer.json /m/tokenizer.json
COPY tools/image-labels/quantize_text.py /m/
RUN python /m/quantize_text.py /m/text_model.onnx /m/fsiglip-text-int8.onnx && rm /m/text_model.onnx

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Seoul
# 임베딩 모델(multilingual-e5-small, 양자화 ONNX 약 118MB)을 이미지에 넣어 뜰 때 내려받지 않게 한다.
# MODEL_BASE_URL: Hugging Face가 막힌 곳에서는 같은 파일을 둔 다른 주소(사내 저장소 등)로 바꿔 빌드한다.
# checksum이 Hugging Face 파일과 같아야만 빌드되므로 어디서 받아도 같은 모델이다.
ARG MODEL_BASE_URL=https://huggingface.co/Xenova/multilingual-e5-small/resolve/main
# 폴더를 먼저 만든다. ADD --chmod가 폴더를 만들면 폴더도 644(들어갈 수 없음)가 되어 USER 1000이 모델을 못 읽는다.
RUN mkdir -m 755 /app/models
ADD --chmod=644 --checksum=sha256:f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193 \
    ${MODEL_BASE_URL}/onnx/model_quantized.onnx /app/models/model.onnx
ADD --chmod=644 --checksum=sha256:0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39 \
    ${MODEL_BASE_URL}/tokenizer.json /app/models/tokenizer.json
COPY --from=text-model --chmod=644 /m/fsiglip-text-int8.onnx /app/models/fsiglip-text-int8.onnx
COPY --from=text-model --chmod=644 /m/tokenizer.json /app/models/fsiglip-tokenizer.json
# 이미지에서는 imageQuery를 기본으로 켠다. 끄려면 CLOSET_IMAGE_TEXT_MODEL_URI를 빈 값으로.
ENV CLOSET_MODEL_DIR=/app/models \
    CLOSET_IMAGE_TEXT_MODEL_URI=file:/app/models/fsiglip-text-int8.onnx \
    CLOSET_IMAGE_TEXT_TOKENIZER_URI=file:/app/models/fsiglip-tokenizer.json \
    DJL_CACHE_DIR=/tmp/djl \
    DJL_OFFLINE=true
COPY --from=build /src/build/libs/mcp-closet-*.jar app.jar
EXPOSE 8095
USER 1000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
