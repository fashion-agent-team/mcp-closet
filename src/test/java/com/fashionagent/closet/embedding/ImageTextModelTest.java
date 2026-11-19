package com.fashionagent.closet.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fashionagent.closet.ClosetProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class ImageTextModelTest {

    static ClosetProperties props(String model, String tokenizer) {
        return new ClosetProperties(0.90, 20, 500, 8, 20, 3,
                new ClosetProperties.Embedding("hash", "", "", "models", 256, Duration.ofMinutes(1)),
                new ClosetProperties.Image(0.89, "", 0.68, 768, 10,
                        new ClosetProperties.Text(model, tokenizer, "marqo-fashionSigLIP", 1.5), 0.10));
    }

    @Test
    @DisplayName("open_clip처럼 문장부호를 빼고 소문자로, 공백은 하나로")
    void canonical() {
        assertThat(ImageTextModel.canonical("A photo of T-shirt, (striped) & cool_stuff!"))
                .isEqualTo("a photo of tshirt striped cool stuff");
    }

    @Test
    @DisplayName("설정이 비면 꺼지고 embed는 null")
    void disabled() throws Exception {
        var m = new ImageTextModel(props("", ""));
        assertThat(m.enabled()).isFalse();
        assertThat(m.embed("jeans")).isNull();
    }

    /** 실제 글 모델로 Python(open_clip 방식 + onnxruntime)과 같은 토큰·벡터가 나오는지. FSIGLIP_TEXT_DIR(text_model.onnx, tokenizer.json). */
    @Test
    @EnabledIfEnvironmentVariable(named = "FSIGLIP_TEXT_DIR", matches = ".+")
    @DisplayName("실제 모델: Python과 같은 토큰 id와 벡터")
    void matchesPython() throws Exception {
        Path dir = Path.of(System.getenv("FSIGLIP_TEXT_DIR"));
        try (var m = new ImageTextModel(props("file:" + dir.resolve("text_model.onnx"), "file:" + dir.resolve("tokenizer.json")))) {
            assertThat(m.ids("a photo of formal office attire for a job interview")).startsWith(
                    262, 266, 1304, 267, 3813, 868, 20113, 270, 262, 266, 689, 2391, 1, 1);
            assertThat(m.ids("a photo of jeans")).hasSize(64).startsWith(262, 266, 1304, 267, 10366, 1, 1);
            float[] v = m.embed("a photo of jeans");
            assertThat(v).hasSize(768);
            // Python int8 모델 값(첫 5개): 0.02316, 0.01143, 0.00704, -0.00792, 0.00373
            assertThat(v[0]).isCloseTo(0.02316f, within(0.002f));
            assertThat(v[3]).isCloseTo(-0.00792f, within(0.002f));
        }
    }
}
