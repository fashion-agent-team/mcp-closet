package com.fashionagent.closet.style;

import com.fashionagent.closet.ClosetProperties;
import com.fashionagent.closet.Vectors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 코디 지식(RAG 문서). 뜰 때 classpath:style_docs/*.md를 closet.style_docs에 맞춰 넣고(바뀐 조각이나 모델이 바뀐 조각만 다시 임베딩),
 * closet_search 때 상황 문장과 가까운 조각을 찾는다.
 */
@Component
public class StyleKnowledge implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StyleKnowledge.class);

    private final VectorStore store;
    private final JdbcClient jdbc;
    /** 어떤 모델로 임베딩했는지. 모델을 바꾸면 글이 같아도 다시 넣는다. */
    private final String modelTag;

    public StyleKnowledge(VectorStore store, JdbcClient jdbc, ClosetProperties props) {
        this.store = store;
        this.jdbc = jdbc;
        var e = props.embedding();
        this.modelTag = "hash".equalsIgnoreCase(e.provider()) ? "hash" : "onnx:" + e.modelUri();
    }

    public record Note(String text, String source) {
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        sync(load());
    }

    static List<StyleDoc> load() throws IOException {
        var docs = new ArrayList<StyleDoc>();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:style_docs/*.md");
        for (Resource r : files) {
            String source = "style_docs/" + r.getFilename();
            docs.add(StyleDoc.parse(source, r.getContentAsString(StandardCharsets.UTF_8)));
        }
        return docs;
    }

    /** 문서와 DB를 맞춘다: 새 조각·바뀐 조각은 넣고(같은 id면 덮어씀), 없어진 조각은 지운다. */
    void sync(List<StyleDoc> docs) {
        Map<String, String> existing = new HashMap<>();
        jdbc.sql("SELECT id::text AS id, content, metadata::jsonb ->> 'embeddingModel' AS model FROM closet.style_docs")
                .query((rs, i) -> Map.entry(rs.getString("id"), rs.getString("model") + "\n" + rs.getString("content")))
                .list().forEach(e -> existing.put(e.getKey(), e.getValue()));

        var changed = new ArrayList<Document>();
        var keep = new java.util.HashSet<String>();
        for (StyleDoc d : docs) {
            for (int i = 0; i < d.chunks().size(); i++) {
                String id = StyleDoc.chunkId(d.source(), i);
                keep.add(id);
                String content = d.chunks().get(i);
                if (!(modelTag + "\n" + content).equals(existing.get(id))) {
                    var meta = new LinkedHashMap<String, Object>(d.meta());
                    meta.put("source", d.source());
                    meta.put("chunkNo", i);
                    meta.put("embeddingModel", modelTag);
                    changed.add(Document.builder().id(id).text(content).metadata(meta).build());
                }
            }
        }
        var stale = existing.keySet().stream().filter(id -> !keep.contains(id)).toList();
        if (!stale.isEmpty()) {
            store.delete(stale);
        }
        if (!changed.isEmpty()) {
            store.add(changed);
        }
        log.info("코디 지식: 문서 {}개, 조각 {}개 (새로·다시 넣음 {}, 지움 {})",
                docs.size(), keep.size(), changed.size(), stale.size());
    }

    /**
     * 상황과 가까운 조각 topK개(자리가 정해졌으면 그 자리 문서와 모든 자리에 해당하는(any) 문서에서만) +
     * 체감 온도가 있으면 그 온도 구간의 날씨 조각 하나. 검색어 벡터는 closet_search가 이미 만들었으므로
     * VectorStore.similaritySearch(다시 임베딩함) 대신 같은 표를 직접 찾는다.
     */
    public List<Note> notesFor(float[] q, String occasion, Double apparentTemperature, int topK) {
        var out = new LinkedHashMap<String, Note>();
        String vector = Vectors.literal(q);
        if (apparentTemperature != null) {
            jdbc.sql("""
                            SELECT id::text AS id, content, metadata::jsonb ->> 'source' AS source FROM closet.style_docs
                             WHERE metadata::jsonb ->> 'occasion' = 'weather'
                               AND (metadata::jsonb ->> 'minTemp')::float8 <= :t AND (metadata::jsonb ->> 'maxTemp')::float8 > :t
                             ORDER BY embedding <=> CAST(:q AS public.vector) LIMIT 1""")
                    .param("t", apparentTemperature).param("q", vector)
                    .query((rs, i) -> Map.entry(rs.getString("id"), new Note(rs.getString("content"), rs.getString("source"))))
                    .list().forEach(e -> out.putIfAbsent(e.getKey(), e.getValue()));
        }
        String where = occasion == null ? "metadata::jsonb ->> 'occasion' <> 'weather'"
                : "metadata::jsonb ->> 'occasion' IN (:occasion, 'any')";
        var spec = jdbc.sql("SELECT id::text AS id, content, metadata::jsonb ->> 'source' AS source FROM closet.style_docs WHERE "
                        + where + " ORDER BY embedding <=> CAST(:q AS public.vector) LIMIT :k")
                .param("q", vector).param("k", topK);
        if (occasion != null) {
            spec = spec.param("occasion", occasion);
        }
        spec.query((rs, i) -> Map.entry(rs.getString("id"), new Note(rs.getString("content"), rs.getString("source"))))
                .list().forEach(e -> out.putIfAbsent(e.getKey(), e.getValue()));
        return List.copyOf(out.values());
    }
}
