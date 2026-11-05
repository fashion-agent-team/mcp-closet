package com.fashionagent.closet.embedding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 모델 없이 띄울 때(CLOSET_EMBEDDING_PROVIDER=hash, 테스트·로컬 개발)의 임베딩.
 * 글자 1~2개 묶음을 384칸에 해싱해서 세므로 같은 글자가 많이 겹치면 가깝다. 뜻은 모르므로 발표·운영에는 쓰지 않는다.
 */
public class HashEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            out.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    static float[] vector(String text) {
        String s = text.replaceFirst("^(query|passage): ", "").replaceAll("[\\s.,()]+", " ").trim();
        float[] v = new float[E5OnnxEmbeddingModel.DIMENSIONS];
        int[] cps = s.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] == ' ') {
                continue;
            }
            add(v, new String(cps, i, 1));
            if (i + 1 < cps.length && cps[i + 1] != ' ') {
                add(v, new String(cps, i, 2));
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < v.length; d++) {
                v[d] /= (float) norm;
            }
        }
        return v;
    }

    private static void add(float[] v, String gram) {
        CRC32 crc = new CRC32();
        crc.update(gram.getBytes(StandardCharsets.UTF_8));
        v[(int) (crc.getValue() % v.length)] += 1;
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return E5.passage(document.getText());
    }

    @Override
    public int dimensions() {
        return E5OnnxEmbeddingModel.DIMENSIONS;
    }
}
