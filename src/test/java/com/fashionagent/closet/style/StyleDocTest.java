package com.fashionagent.closet.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StyleDocTest {

    @Test
    @DisplayName("머리말·제목·소제목으로 조각을 나누고 조각마다 제목을 붙인다")
    void parse() {
        var d = StyleDoc.parse("style_docs/x.md", """
                ---
                occasion: weather
                minTemp: 9
                maxTemp: 12
                ---
                # 체감 9~12도

                ## 옷차림
                트렌치코트가 좋다.
                안에는 셔츠.

                ## 신발
                운동화.
                """);
        assertThat(d.title()).isEqualTo("체감 9~12도");
        assertThat(d.meta()).containsEntry("occasion", "weather").containsEntry("minTemp", 9.0).containsEntry("maxTemp", 12.0);
        assertThat(d.chunks()).containsExactly("체감 9~12도 - 옷차림: 트렌치코트가 좋다. 안에는 셔츠.", "체감 9~12도 - 신발: 운동화.");
        assertThat(StyleDoc.chunkId("style_docs/x.md", 0)).isEqualTo(StyleDoc.chunkId("style_docs/x.md", 0))
                .isNotEqualTo(StyleDoc.chunkId("style_docs/x.md", 1));
    }

    @Test
    @DisplayName("들어 있는 코디 지식 문서는 모두 occasion이 있고, 날씨 문서 온도 구간은 빈틈없이 이어진다")
    void bundledDocs() throws Exception {
        var docs = StyleKnowledge.load();
        assertThat(docs).hasSize(17);
        var occasions = new HashSet<Object>();
        docs.forEach(d -> {
            assertThat(d.chunks()).as(d.source()).isNotEmpty();
            occasions.add(d.meta().get("occasion"));
        });
        assertThat(occasions).contains("formal", "wedding", "mourning", "business", "active", "casual", "any", "weather");
        var bands = docs.stream().filter(d -> "weather".equals(d.meta().get("occasion")))
                .sorted((a, b) -> Double.compare((Double) a.meta().get("minTemp"), (Double) b.meta().get("minTemp"))).toList();
        for (int i = 1; i < bands.size(); i++) {
            assertThat(bands.get(i).meta().get("minTemp")).isEqualTo(bands.get(i - 1).meta().get("maxTemp"));
        }
    }
}
