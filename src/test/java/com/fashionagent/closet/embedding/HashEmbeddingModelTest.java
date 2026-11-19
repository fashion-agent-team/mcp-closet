package com.fashionagent.closet.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashEmbeddingModelTest {

    static double cos(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    @Test
    @DisplayName("384차원, 길이 1, 같은 글이면 같은 벡터, 접두어는 무시")
    void basics() {
        var m = new HashEmbeddingModel();
        float[] a = m.embed("query: 오늘 면접");
        assertThat(a).hasSize(384);
        assertThat(cos(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(cos(a, m.embed("passage: 오늘 면접"))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(cos(a, m.embed("오늘 면접 정장"))).isGreaterThan(cos(a, m.embed("등산 운동화")));
    }

    @Test
    @DisplayName("평균 풀링은 mask 0인 토큰을 빼고 길이 1로 맞춘다")
    void meanPool() {
        float[] v = E5OnnxEmbeddingModel.meanPoolNormalize(new float[][] {{3, 0}, {1, 0}, {100, 100}}, new long[] {1, 1, 0});
        assertThat(v[0]).isEqualTo(1.0f);
        assertThat(v[1]).isEqualTo(0.0f);
    }
}
