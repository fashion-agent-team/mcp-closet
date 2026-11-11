package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ImageLabelsTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ImageLabels labels = new ImageLabels(json);

    private float[] vectorOf(String section, String ko) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ImageLabels.RESOURCE)) {
            for (JsonNode n : json.readTree(in).get(section)) {
                if (ko.equals(n.get("ko").asString())) {
                    float[] v = new float[n.get("vector").size()];
                    for (int i = 0; i < v.length; i++) {
                        v[i] = (float) n.get("vector").get(i).asDouble();
                    }
                    return Vectors.normalize(v);
                }
            }
        }
        throw new IllegalArgumentException(ko);
    }

    @Test
    @DisplayName("표가 FashionSigLIP 768차원 이름 22개, 무늬 5개로 뜬다")
    void loads() {
        assertThat(labels.model()).isEqualTo("marqo-fashionSigLIP");
        assertThat(labels.sizes()).containsEntry("names", 22).containsEntry("patterns", 5);
    }

    @Test
    @DisplayName("이름 벡터와 같은 사진이면 그 이름, 종류에 안 맞는 이름은 고르지 않는다")
    void names() throws Exception {
        float[] jeans = vectorOf("names", "청바지");
        assertThat(labels.guess(jeans, "bottom").name()).isEqualTo("청바지");
        assertThat(labels.guess(jeans, "bottom").formality()).isEqualTo("casual");
        assertThat(labels.guess(jeans, "top").name()).isNotEqualTo("청바지");
        assertThat(labels.guess(vectorOf("names", "정장 자켓"), "outer").formality()).isEqualTo("formal");
        // 치마는 격식을 정하지 않는다(unknown → null)
        assertThat(labels.guess(vectorOf("names", "치마"), "bottom").formality()).isNull();
        // 신발 이름은 표에 없다
        assertThat(labels.guess(jeans, "shoes").name()).isNull();
    }

    @Test
    @DisplayName("무늬: 줄무늬 벡터면 줄무늬, 무지 벡터면 null")
    void patterns() throws Exception {
        assertThat(labels.guess(vectorOf("patterns", "줄무늬"), "top").pattern()).isEqualTo("줄무늬");
        float[] plain = vectorOf("names", "티셔츠");
        assertThat(labels.guess(plain, "top").pattern()).isNull();
    }
}
