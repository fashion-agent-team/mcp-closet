package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageMatcherTest {

    private static ClosetProperties props(int dim, String adapter) {
        return new ClosetProperties(0.90, 20, 500, 8, 20, 3,
                new ClosetProperties.Embedding("hash", "", "", "models", 256, java.time.Duration.ofMinutes(1)),
                new ClosetProperties.Image(0.89, adapter, 0.43, dim, 10));
    }

    @Test
    @DisplayName("길이가 다르거나 숫자가 아니면 받지 않고, 받으면 길이 1로 맞춘다")
    void accept() {
        var m = new ImageMatcher(props(3, ""));
        assertThat(m.accept(null)).isNull();
        assertThat(m.accept(List.of(1.0, 2.0))).isNull();
        var withNull = new ArrayList<Double>(List.of(1.0, 0.0));
        withNull.add(null);
        assertThat(m.accept(withNull)).isNull();
        assertThat(m.accept(List.of(0.0, 0.0, 0.0))).isNull();
        assertThat(m.accept(List.of(3.0, 4.0, 0.0))).containsExactly(0.6f, 0.8f, 0.0f);
        assertThat(m.adapted()).isFalse();
        assertThat(m.threshold()).isEqualTo(0.89);
    }

    @Test
    @DisplayName("평균은 사진 수만큼 가중하고 maxCount에서 멈춘다")
    void mean() {
        float[] a = {1, 0};
        float[] b = {0, 1};
        assertThat(ImageMatcher.mean(null, 0, a, 10)).containsExactly(1f, 0f);
        assertThat(ImageMatcher.mean(a, 1, b, 10)).containsExactly(0.5f, 0.5f);
        assertThat(ImageMatcher.mean(a, 3, b, 10)).containsExactly(0.75f, 0.25f);
        // 10장째부터는 새 사진 비중이 1/10
        assertThat(ImageMatcher.mean(a, 50, b, 10)[1]).isEqualTo(0.1f);
    }

    @Test
    @DisplayName("보정 행렬 파일: (v − mu)·W 후 길이 1, 기준은 adapterThreshold")
    void adapter(@TempDir Path dir) throws Exception {
        // d=2, mu=(0.5,0), W=[[2,0],[0,1]] (행 우선)
        var buf = ByteBuffer.allocate(4 * 6).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : new float[] {0.5f, 0f, 2f, 0f, 0f, 1f}) {
            buf.putFloat(x);
        }
        Path f = dir.resolve("a.bin");
        Files.write(f, buf.array());
        var m = new ImageMatcher(props(2, f.toString()));
        assertThat(m.adapted()).isTrue();
        assertThat(m.threshold()).isEqualTo(0.43);
        // v=(0.6,0.8) → c=(0.1,0.8) → (0.2,0.8) → 길이 1
        float[] k = m.key(new float[] {0.6f, 0.8f});
        double n = Math.sqrt(0.2 * 0.2 + 0.8 * 0.8);
        assertThat(k[0]).isCloseTo((float) (0.2 / n), org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(k[1]).isCloseTo((float) (0.8 / n), org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    @DisplayName("기본 보정 행렬(jar 안, CC0로 만듦)을 읽고 기준 0.68을 쓴다. 같은 벡터는 1, 길이 1 유지")
    void defaultAdapter() {
        var m = new ImageMatcher(new ClosetProperties(0.90, 20, 500, 8, 20, 3,
                new ClosetProperties.Embedding("hash", "", "", "models", 256, java.time.Duration.ofMinutes(1)),
                new ClosetProperties.Image(0.89, "classpath:image-adapter/fsiglip-cc0-aug.bin", 0.68, 768, 10)));
        assertThat(m.adapted()).isTrue();
        assertThat(m.threshold()).isEqualTo(0.68);
        var v = new ArrayList<Double>();
        for (int i = 0; i < 768; i++) {
            v.add(Math.sin(i * 0.37));
        }
        float[] k = m.key(m.accept(v));
        assertThat(Vectors.dot(k, k)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("보정 행렬 크기가 틀리면 서버가 뜨지 않는다(조용히 기준이 어긋나지 않게)")
    void badAdapter(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bad.bin");
        Files.write(f, new byte[12]);
        assertThatThrownBy(() -> new ImageMatcher(props(2, f.toString()))).hasMessageContaining("크기");
    }
}
