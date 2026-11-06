package com.fashionagent.closet.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * multilingual-e5-small(384차원)을 ONNX Runtime으로 서버 CPU에서 돌린다.
 * 토큰 벡터를 attention mask로 평균 낸 뒤 길이 1로 맞춘다(e5 모델 카드의 average_pool + normalize).
 * 문서(코디 지식)는 "passage: "를 붙여 임베딩하고, 검색어는 부르는 쪽이 "query: "를 붙인다.
 */
public class E5OnnxEmbeddingModel implements EmbeddingModel, AutoCloseable {

    public static final int DIMENSIONS = 384;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean needsTokenTypeIds;
    private final String outputName;

    public E5OnnxEmbeddingModel(Path model, Path tokenizerJson, int maxTokens) throws IOException, OrtException {
        try (InputStream in = Files.newInputStream(tokenizerJson)) {
            this.tokenizer = HuggingFaceTokenizer.newInstance(in, Map.of(
                    "maxLength", String.valueOf(maxTokens), "truncation", "true", "padding", "true", "modelMaxLength", String.valueOf(maxTokens)));
        }
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            this.session = env.createSession(model.toString(), options);
        }
        this.needsTokenTypeIds = session.getInputNames().contains("token_type_ids");
        var outputs = session.getOutputNames();
        this.outputName = outputs.contains("last_hidden_state") ? "last_hidden_state" : outputs.iterator().next();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> out = new ArrayList<>(texts.size());
        // 한 번에 너무 많이 넣으면 패딩 때문에 메모리가 커져서 16개씩 나눈다.
        for (int start = 0; start < texts.size(); start += 16) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + 16));
            float[][] vectors = embedBatch(batch);
            for (int i = 0; i < vectors.length; i++) {
                out.add(new Embedding(vectors[i], start + i));
            }
        }
        return new EmbeddingResponse(out);
    }

    private float[][] embedBatch(List<String> texts) {
        Encoding[] enc = tokenizer.batchEncode(texts);
        int n = enc.length;
        long[][] ids = new long[n][];
        long[][] mask = new long[n][];
        long[][] types = new long[n][];
        for (int i = 0; i < n; i++) {
            ids[i] = enc[i].getIds();
            mask[i] = enc[i].getAttentionMask();
            types[i] = new long[ids[i].length];
        }
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, ids));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, mask));
            if (needsTokenTypeIds) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, types));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(outputName).orElseThrow().getValue();
                float[][] vectors = new float[n][];
                for (int i = 0; i < n; i++) {
                    vectors[i] = meanPoolNormalize(hidden[i], mask[i]);
                }
                return vectors;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("임베딩 실패: " + e.getMessage(), e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    static float[] meanPoolNormalize(float[][] tokens, long[] mask) {
        int dim = tokens[0].length;
        float[] sum = new float[dim];
        int count = 0;
        for (int t = 0; t < tokens.length && t < mask.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < dim; d++) {
                sum[d] += tokens[t][d];
            }
        }
        double norm = 0;
        for (int d = 0; d < dim; d++) {
            sum[d] /= Math.max(1, count);
            norm += sum[d] * sum[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < dim; d++) {
                sum[d] /= (float) norm;
            }
        }
        return sum;
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    /** PgVectorStore가 코디 지식 조각을 넣을 때 부른다. */
    @Override
    public String getEmbeddingContent(Document document) {
        return E5.passage(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public void close() throws OrtException {
        tokenizer.close();
        session.close();
    }
}
