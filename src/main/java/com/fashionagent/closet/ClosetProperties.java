package com.fashionagent.closet;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yml의 closet.* 설정.
 *
 * @param sameGarmentThreshold 같은 옷으로 합치는 코사인 유사도 기준. 종류·색 계열·이름 묶음이 같을 때만 비교(실제 e5로 잰 값은 E5OnnxEmbeddingModelTest).
 * @param maxGarmentsPerSave   closet_save 한 번에 받는 옷 수
 * @param maxClosetSize        한 사람 옷장 최대 벌 수
 * @param defaultLimit         closet_search 후보 기본 개수
 * @param maxLimit             closet_search 후보 최대 개수
 * @param styleNotes           closet_search가 돌려주는 코디 지식 조각 수(날씨 조각 제외)
 * @param embedding            임베딩 모델 설정
 * @param image                옷 사진 벡터로 같은 옷 판단(clothes_inspect garments[].embedding)
 */
@ConfigurationProperties("closet")
public record ClosetProperties(
        @DefaultValue("0.90") double sameGarmentThreshold,
        @DefaultValue("20") int maxGarmentsPerSave,
        @DefaultValue("500") int maxClosetSize,
        @DefaultValue("8") int defaultLimit,
        @DefaultValue("20") int maxLimit,
        @DefaultValue("3") int styleNotes,
        @DefaultValue Embedding embedding,
        @DefaultValue Image image) {

    /**
     * @param provider     onnx(multilingual-e5-small) | hash(모델 없이 개발·테스트용, 뜻은 모름)
     * @param modelUri     ONNX 파일. http(s)면 cacheDir에 한 번 내려받는다. file:·classpath:도 됨
     * @param tokenizerUri tokenizer.json
     * @param cacheDir     내려받은 모델을 두는 곳(Docker에서는 볼륨)
     * @param maxTokens    한 문장 최대 토큰 수(옷 설명·코디 조각은 짧아서 256이면 충분)
     * @param downloadTimeout 모델 내려받기 제한 시간
     */
    public record Embedding(
            @DefaultValue("onnx") String provider,
            @DefaultValue("https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx") String modelUri,
            @DefaultValue("https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json") String tokenizerUri,
            @DefaultValue("models") String cacheDir,
            @DefaultValue("256") int maxTokens,
            @DefaultValue("10m") Duration downloadTimeout) {
    }

    /**
     * 옷 사진 벡터 설정. 기준값은 DeepFashion 옷 600벌로 "옷 30벌 옷장에 새 옷이 잘못 합쳐지는 비율 5%"에 맞춘 값.
     *
     * @param threshold         보정 행렬 없이 비교할 때 같은 옷으로 보는 코사인 유사도
     * @param adapterPath       보정 행렬(WCCN). 기본은 jar 안의 CC0 옷 사진으로 만든 것(classpath:). 파일 경로도 됨. 비우면 쓰지 않음
     * @param adapterThreshold  보정 행렬을 쓸 때의 기준(보정 후 유사도는 크기가 달라서 따로 둔다). 행렬을 바꾸면 기준도 함께 바꾼다
     * @param dimensions        벡터 길이(DB 열 vector(768)과 같아야 함)
     * @param maxCount          평균에 넣는 사진 수 상한. 넘으면 새 사진 비중을 1/maxCount로 고정(옷이 바래도 따라감)
     */
    public record Image(
            @DefaultValue("0.89") double threshold,
            @DefaultValue("classpath:image-adapter/fsiglip-cc0-aug.bin") String adapterPath,
            @DefaultValue("0.68") double adapterThreshold,
            @DefaultValue("768") int dimensions,
            @DefaultValue("10") int maxCount) {
    }
}
