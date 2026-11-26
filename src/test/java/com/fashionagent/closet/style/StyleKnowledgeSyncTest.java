package com.fashionagent.closet.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.fashionagent.closet.TestDb;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 코디 지식 맞추기(StyleKnowledge.sync): 본문이 같아도 머리말(occasion, minTemp·maxTemp)만 바뀌면 그 조각을 다시 넣는지.
 * 진짜 PostgreSQL(pgvector)이 필요하다. DB가 없으면(TestDb#available) 건너뛴다.
 */
@SpringBootTest(properties = {"closet.embedding.provider=hash", "closet.image.adapter-path="})
@Import(TestDb.class)
@EnabledIf("com.fashionagent.closet.TestDb#available")
class StyleKnowledgeSyncTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @Autowired
    StyleKnowledge knowledge;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void restore() throws IOException {
        knowledge.sync(StyleKnowledge.load());
    }

    @Test
    @DisplayName("아무것도 안 바뀌면 다시 넣는 조각은 0개")
    void unchangedDocsAreNotReinserted() throws IOException {
        knowledge.sync(StyleKnowledge.load());
        assertThat(knowledge.sync(StyleKnowledge.load())).isZero();
    }

    @Test
    @DisplayName("같은 본문에 머리말만 바꾸면 그 문서 조각만 다시 넣고 metadata도 새 값이 된다")
    void headerOnlyChangeReinsertsThatDocument() throws IOException {
        List<StyleDoc> docs = StyleKnowledge.load();
        knowledge.sync(docs);
        StyleDoc weather = docs.stream().filter(d -> d.meta().containsKey("maxTemp")).findFirst().orElseThrow();

        var meta = new LinkedHashMap<String, Object>(weather.meta());
        meta.put("maxTemp", (Double) weather.meta().get("maxTemp") + 0.5);
        var changed = new ArrayList<StyleDoc>();
        for (StyleDoc d : docs) {
            changed.add(d == weather ? new StyleDoc(d.source(), d.title(), meta, d.chunks()) : d);
        }

        assertThat(knowledge.sync(changed)).isEqualTo(weather.chunks().size());
        Double stored = jdbc.sql("SELECT (metadata::jsonb ->> 'maxTemp')::float8 FROM closet.style_docs WHERE id = CAST(:id AS uuid)")
                .param("id", StyleDoc.chunkId(weather.source(), 0)).query(Double.class).single();
        assertThat(stored).isEqualTo((Double) meta.get("maxTemp"));
        assertThat(knowledge.sync(changed)).isZero();
    }

    @Test
    @DisplayName("머리말 해시는 키 순서와 상관없이 같고, 값이 바뀌면 달라진다")
    void metaHashIgnoresKeyOrder() {
        var a = new LinkedHashMap<String, Object>();
        a.put("occasion", "weather");
        a.put("minTemp", 9.0);
        var b = new LinkedHashMap<String, Object>();
        b.put("minTemp", 9.0);
        b.put("occasion", "weather");
        assertThat(StyleKnowledge.metaHash(a)).isEqualTo(StyleKnowledge.metaHash(b)).hasSize(16);
        b.put("minTemp", 10.0);
        assertThat(StyleKnowledge.metaHash(a)).isNotEqualTo(StyleKnowledge.metaHash(b));
    }
}
