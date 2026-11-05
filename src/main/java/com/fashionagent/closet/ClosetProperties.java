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
 */
@ConfigurationProperties("closet")
public record ClosetProperties(
        @DefaultValue("0.90") double sameGarmentThreshold,
        @DefaultValue("20") int maxGarmentsPerSave,
        @DefaultValue("500") int maxClosetSize,
        @DefaultValue("8") int defaultLimit,
        @DefaultValue("20") int maxLimit,
        @DefaultValue("3") int styleNotes,
        @DefaultValue Embedding embedding) {

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
}
