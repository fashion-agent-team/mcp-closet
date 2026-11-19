package com.fashionagent.closet.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fashionagent.closet.ClosetProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FashionSigLIP 글 쪽. 영어 검색어를 옷 사진 벡터(clothes_inspect embedding)와 같은 공간의 768차원으로 바꾼다.
 * open_clip의 SigLIP 전처리를 따른다: 문장부호 빼기·소문자·공백 하나 → T5 sentencepiece(끝에 &lt;/s&gt;) → 64토큰, 빈칸은 1(&lt;/s&gt;).
 * 설정(closet.image.text.model-uri)이 비면 꺼져 있고 {@link #enabled()}가 false.
 */
@Component
public class ImageTextModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ImageTextModel.class);
    static final int LENGTH = 64;
    static final long PAD = 1;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final String model;

    public ImageTextModel(ClosetProperties props) throws Exception {
        var t = props.image().text();
        this.model = t.model();
        if (t.modelUri() == null || t.modelUri().isBlank()) {
            session = null;
            tokenizer = null;
            log.info("사진↔글 검색(imageQuery): 꺼짐 (CLOSET_IMAGE_TEXT_MODEL_URI 없음)");
            return;
        }
        Path dir = Path.of(props.embedding().cacheDir());
        Path m = EmbeddingConfig.fetch(t.modelUri(), dir.resolve("fsiglip-text.onnx"), props.embedding());
        Path tok = EmbeddingConfig.fetch(t.tokenizerUri(), dir.resolve("fsiglip-tokenizer.json"), props.embedding());
        try (InputStream in = Files.newInputStream(tok)) {
            tokenizer = HuggingFaceTokenizer.newInstance(in, Map.of("truncation", "false", "padding", "false"));
        }
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            session = env.createSession(m.toString(), options);
        }
        log.info("사진↔글 검색(imageQuery): {} 준비", model);
    }

    public boolean enabled() {
        return session != null;
    }

    /** 이 글 모델과 짝인 사진 벡터 이름(closet.garments.image_model). */
    public String model() {
        return model;
    }

    /** open_clip canonicalize_text와 같게. */
    static String canonical(String text) {
        String s = text.replace('_', ' ')
                .replaceAll("[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^`{|}~]", "").toLowerCase(Locale.ROOT);
        return String.join(" ", s.trim().split("\\s+"));
    }

    long[] ids(String text) {
        long[] raw = tokenizer.encode(canonical(text)).getIds();
        long[] out = new long[LENGTH];
        Arrays.fill(out, PAD);
        System.arraycopy(raw, 0, out, 0, Math.min(LENGTH, raw.length));
        return out;
    }

    /** 길이 1인 768차원. 꺼져 있으면 null. */
    public float[] embed(String text) {
        if (!enabled()) {
            return null;
        }
        try (OnnxTensor x = OnnxTensor.createTensor(env, new long[][] {ids(text)});
             OrtSession.Result r = session.run(Map.of("input_ids", x))) {
            String out = session.getOutputNames().contains("text_embeds") ? "text_embeds" : session.getOutputNames().iterator().next();
            float[] v = ((float[][]) r.get(out).orElseThrow().getValue())[0];
            double n = 0;
            for (float f : v) {
                n += f * f;
            }
            n = Math.sqrt(n);
            for (int i = 0; i < v.length; i++) {
                v[i] /= (float) n;
            }
            return v;
        } catch (OrtException e) {
            throw new IllegalStateException("글 임베딩 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws OrtException {
        if (session != null) {
            session.close();
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
    }
}
