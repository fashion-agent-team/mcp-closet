package com.fashionagent.closet.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 multilingual-e5-small로 돌려 본다. 모델이 커서 기본으로는 건너뛰고,
 * CLOSET_E5_DIR(model.onnx, tokenizer.json이 있는 폴더)을 주면 돈다. 같은 옷 합치기 기준값(0.90)을 정할 때 쓴 숫자도 찍는다.
 */
@EnabledIfEnvironmentVariable(named = "CLOSET_E5_DIR", matches = ".+")
class E5OnnxEmbeddingModelTest {

    static E5OnnxEmbeddingModel model;

    @BeforeAll
    static void load() throws Exception {
        Path dir = Path.of(System.getenv("CLOSET_E5_DIR"));
        model = new E5OnnxEmbeddingModel(dir.resolve("model.onnx"), dir.resolve("tokenizer.json"), 256);
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    static double cos(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    static String garment(String name, String color, String tone, String formality, String material) {
        return "passage: 상의 " + name + ". 색: " + color + " (" + tone + "). 격식: " + formality + "."
                + (material == null ? "" : " 소재: " + material + ".");
    }

    @Test
    @DisplayName("384차원·길이 1, 한 번에 여러 개를 넣어도 하나씩 넣은 것과 같다")
    void shape() {
        var batch = model.embed(List.of("query: 면접", "passage: 짧은 글", "passage: 조금 더 긴 글이라 패딩이 붙는다"));
        float[] single = model.embed("passage: 짧은 글");
        assertThat(batch.get(1)).hasSize(384);
        assertThat(cos(single, single)).isBetween(0.999, 1.001);
        // 양자화 모델은 활성값을 묶음 단위로 양자화해서 함께 넣은 글에 따라 아주 조금 달라진다
        System.out.printf("e5 batch-vs-single %.4f%n", cos(batch.get(1), single));
        assertThat(cos(batch.get(1), single)).isGreaterThan(0.98);
    }

    @Test
    @DisplayName("같은 옷을 다르게 적어도 유사도 0.90 이상 (기준값 확인용 숫자 출력)")
    void sameGarment() {
        float[] knit = model.embed(garment("니트", "차콜 그레이", "어두운 톤", "단정한 옷, 출근·모임", "니트 같음"));
        var same = List.of(
                garment("스웨터", "진회색", "어두운 톤", "단정한 옷, 출근·모임", "니트"),
                garment("니트", "차콜", "중간 톤", "단정한 옷, 출근·모임", null),
                garment("니트 스웨터", "짙은 회색", "어두운 톤", "격식 모름", "울 같음"));
        var other = List.of(
                garment("가디건", "차콜 그레이", "어두운 톤", "단정한 옷, 출근·모임", "니트 같음"),
                garment("후드티", "차콜 그레이", "어두운 톤", "편한 옷, 일상", "면 같음"),
                garment("맨투맨", "회색", "중간 톤", "편한 옷, 일상", "면"));
        for (String g : same) {
            double s = cos(knit, model.embed(g));
            System.out.printf("e5 same  %.4f %s%n", s, g);
            assertThat(s).isGreaterThanOrEqualTo(0.90);
        }
        // 다른 옷도 0.90을 넘는 경우가 있다(같은 색 가디건 약 0.98). 그래서 이름 묶음(GarmentNames)으로 한 번 더 거른다.
        for (String g : other) {
            System.out.printf("e5 other %.4f %s%n", cos(knit, model.embed(g)), g);
        }
    }

    @Test
    @DisplayName("상황 문장으로 찾으면 맞는 코디 지식이 위로 온다")
    void retrieval() {
        float[] q = model.embed("query: 오늘 오전 면접, 체감 9도");
        double interview = cos(q, model.embed("passage: 면접·발표 옷차림 - 기본: 면접에는 어두운 색 슬랙스와 셔츠, 단정한 자켓이 무난하다."));
        double hiking = cos(q, model.embed("passage: 운동·등산 옷차림 - 기본: 운동이나 등산에는 땀이 잘 마르고 움직이기 편한 운동복과 운동화가 좋다."));
        System.out.printf("e5 retrieval interview=%.4f hiking=%.4f%n", interview, hiking);
        assertThat(interview).isGreaterThan(hiking);
    }
}
