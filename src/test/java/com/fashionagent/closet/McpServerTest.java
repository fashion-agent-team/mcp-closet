package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제로 서버와 PostgreSQL(pgvector)을 띄우고 MCP 클라이언트로 /mcp에 붙어 본다(agent가 붙는 방식과 같음).
 * 임베딩은 모델 없이 도는 hash를 쓴다(뜻은 모르지만 같은 글은 같은 벡터). Docker가 없으면 건너뛴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"closet.embedding.provider=hash", "closet.image.adapter-path="})
@Import(TestDb.class)
@Testcontainers(disabledWithoutDocker = true)
class McpServerTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    McpSyncClient client;
    String user;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM closet.wears").update();
        jdbc.sql("DELETE FROM closet.garments").update();
        user = UUID.randomUUID().toString();
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build();
        client = McpClient.sync(transport).build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    @DisplayName("tools list에 closet 도구 4개가 있고 필수 입력이 맞다")
    @SuppressWarnings("unchecked")
    void toolsList() {
        var tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("closet_search", "closet_save", "closet_wear", "closet_outfits", "closet_list", "closet_remove");
        var outfits = tools.stream().filter(t -> t.name().equals("closet_outfits")).findFirst().orElseThrow();
        assertThat((List<String>) outfits.inputSchema().get("required")).containsExactly("userId");
        var search = tools.stream().filter(t -> t.name().equals("closet_search")).findFirst().orElseThrow();
        assertThat((List<String>) search.inputSchema().get("required")).containsExactlyInAnyOrder("userId", "situation");
        assertThat(((Map<String, Object>) search.inputSchema().get("properties")).keySet()).contains("imageQuery");
        var save = tools.stream().filter(t -> t.name().equals("closet_save")).findFirst().orElseThrow();
        assertThat((List<String>) save.inputSchema().get("required")).containsExactlyInAnyOrder("userId", "garments");
    }

    @Test
    @DisplayName("코디 지식이 뜰 때 들어가 있다(문서 17개)")
    void styleDocsLoaded() {
        int sources = jdbc.sql("SELECT count(DISTINCT metadata::jsonb ->> 'source') FROM closet.style_docs").query(Integer.class).single();
        assertThat(sources).isEqualTo(17);
    }

    @Test
    @DisplayName("빈 옷장: candidates 없음, empty true, 코디 지식과 체감 온도 구간 날씨 조각은 있음")
    void emptyCloset() {
        JsonNode r = call("closet_search", Map.of("userId", user, "situation", "오늘 오전 면접, 체감 9도",
                "occasion", "formal", "apparentTemperature", 9.5));
        assertThat(r.get("empty").asBoolean()).isTrue();
        assertThat(r.get("candidates").size()).isZero();
        var sources = new ArrayList<String>();
        r.get("styleNotes").forEach(n -> sources.add(n.get("source").asString()));
        assertThat(sources).contains("style_docs/weather-9to12.md");
        // formal이면 formal·any 문서와 날씨 조각만
        assertThat(sources).allMatch(s -> List.of("style_docs/interview.md", "style_docs/weather-9to12.md",
                "style_docs/color-basics.md", "style_docs/color-combos.md", "style_docs/pattern-material.md").contains(s));
        // 비 이야기가 있을 때만 비 문서
        // imageQuery는 글 모델이 꺼져 있으면 무시하고 그대로 찾는다
        JsonNode rainy = call("closet_search", Map.of("userId", user, "situation", "오후에 비 와요, 출근", "occasion", "business",
                "imageQuery", "office clothes for a rainy day"));
        var rainSources = new ArrayList<String>();
        rainy.get("styleNotes").forEach(n -> rainSources.add(n.get("source").asString()));
        assertThat(rainSources).contains("style_docs/rain.md");
        assertThat(r.get("styleNotes").get(0).get("text").asString()).isNotBlank();
    }

    @Test
    @DisplayName("저장 → 같은 옷을 다시 저장하면 합치고(isNew false), 다른 옷은 새로 만든다")
    void saveAndMerge() {
        JsonNode first = call("closet_save", Map.of("userId", user, "garments", List.of(
                garment("g1", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음"),
                garment("g2", "bottom", "슬랙스", "검은색", "black", "dark", "formal", null))));
        assertThat(first.get("saved").size()).isEqualTo(2);
        assertThat(first.get("saved").get(0).get("inputId").asString()).isEqualTo("g1");
        assertThat(first.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        String knit = first.get("saved").get(0).get("garmentId").asString();
        assertThat(knit).startsWith("c_");

        // 같은 니트를 다음 날 다시 봄(얼룩이 생김) → 합침, 설명은 마지막 것으로
        var again = garment("g7", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음");
        again.put("defects", List.of(Map.of("kind", "stain", "where", "오른쪽 소매 끝", "confidence", 0.85)));
        JsonNode second = call("closet_save", Map.of("userId", user, "garments", List.of(again,
                garment("g8", "top", "반팔 티셔츠", "회색", "gray", "light", "casual", "면"))));
        assertThat(second.get("saved").get(0).get("garmentId").asString()).isEqualTo(knit);
        assertThat(second.get("saved").get(0).get("isNew").asBoolean()).isFalse();
        assertThat(second.get("saved").get(1).get("isNew").asBoolean()).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = CAST(:u AS uuid)").param("u", user)
                .query(Integer.class).single()).isEqualTo(3);
        String desc = jdbc.sql("SELECT description::text FROM closet.garments WHERE id = :id").param("id", knit)
                .query(String.class).single();
        assertThat(desc).contains("stain", "오른쪽 소매 끝");
        assertThat(jdbc.sql("SELECT description ->> 'id' FROM closet.garments WHERE id = :id").param("id", knit)
                .query(String.class).single()).isEqualTo(knit);

        // 다른 사람 옷장과는 합치지 않는다
        String other = UUID.randomUUID().toString();
        JsonNode otherSave = call("closet_save", Map.of("userId", other, "garments", List.of(
                garment("g1", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음"))));
        assertThat(otherSave.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        assertThat(otherSave.get("saved").get(0).get("garmentId").asString()).isNotEqualTo(knit);
    }

    @Test
    @DisplayName("사진 벡터가 있으면 사진으로 판단: 색 판별이 달라도 같은 옷은 합치고, 같은 색 흰 티셔츠라도 사진이 다르면 새 옷")
    void imageMatch() {
        List<Double> shirtA = vector(1, 0.0);
        // 같은 셔츠를 다른 날 찍음: 벡터는 조금 다르고(코사인 약 0.95) clothes_inspect가 색을 베이지로 봄
        List<Double> shirtA2 = vector(1, 0.33);
        List<Double> shirtB = vector(2, 0.0);   // 다른 흰 티셔츠(코사인 0)

        var first = garment("g1", "top", "반팔 티셔츠", "흰색", "white", "light", "casual", "면");
        first.put("embedding", shirtA);
        JsonNode r1 = call("closet_save", Map.of("userId", user, "garments", List.of(first), "embeddingModel", "marqo-fashionSigLIP"));
        String a = r1.get("saved").get(0).get("garmentId").asString();

        var again = garment("g1", "top", "티셔츠", "베이지", "beige", "mid", "casual", "면");
        again.put("embedding", shirtA2);
        JsonNode r2 = call("closet_save", Map.of("userId", user, "garments", List.of(again), "embeddingModel", "marqo-fashionSigLIP"));
        assertThat(r2.get("saved").get(0).get("garmentId").asString()).isEqualTo(a);
        assertThat(r2.get("saved").get(0).get("isNew").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT image_count FROM closet.garments WHERE id = :id").param("id", a).query(Integer.class).single())
                .isEqualTo(2);
        // 사진 벡터는 description(jsonb)에 넣지 않는다
        assertThat(jdbc.sql("SELECT description::text FROM closet.garments WHERE id = :id").param("id", a).query(String.class).single())
                .doesNotContain("embedding");

        var other = garment("g1", "top", "반팔 티셔츠", "흰색", "white", "light", "casual", "면");
        other.put("embedding", shirtB);
        JsonNode r3 = call("closet_save", Map.of("userId", user, "garments", List.of(other), "embeddingModel", "marqo-fashionSigLIP"));
        assertThat(r3.get("saved").get(0).get("isNew").asBoolean()).isTrue();

        // 모델 이름이 다르면 비교하지 않는다(새 옷)
        var otherModel = garment("g1", "top", "반팔 티셔츠", "흰색", "white", "light", "casual", "면");
        otherModel.put("embedding", shirtA);
        JsonNode r4 = call("closet_save", Map.of("userId", user, "garments", List.of(otherModel), "embeddingModel", "other-model"));
        assertThat(r4.get("saved").get(0).get("isNew").asBoolean()).isTrue();

        // forceNewIds는 사진이 같아도 새 옷
        var forced = garment("g9", "top", "반팔 티셔츠", "흰색", "white", "light", "casual", "면");
        forced.put("embedding", shirtA);
        JsonNode r5 = call("closet_save", Map.of("userId", user, "garments", List.of(forced), "embeddingModel", "marqo-fashionSigLIP",
                "forceNewIds", List.of("g9")));
        assertThat(r5.get("saved").get(0).get("isNew").asBoolean()).isTrue();

        // 길이가 틀린 벡터는 무시하고 예전 규칙으로 저장(오류 아님)
        var bad = garment("g1", "bottom", "청바지", "파란색", "blue", "mid", "casual", "데님");
        bad.put("embedding", List.of(0.1, 0.2));
        JsonNode r6 = call("closet_save", Map.of("userId", user, "garments", List.of(bad), "embeddingModel", "marqo-fashionSigLIP"));
        assertThat(r6.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        String jeans = r6.get("saved").get(0).get("garmentId").asString();
        assertThat(jdbc.sql("SELECT image_model FROM closet.garments WHERE id = :id").param("id", jeans).query(String.class).optional())
                .isEmpty();
    }

    @Test
    @DisplayName("clothes_inspect 응답의 옷을 그대로 넘겨도 받는다(defects.checkedBy, box, position, confidence, embedding)")
    void acceptsClothesInspectGarment() {
        var g = new LinkedHashMap<String, Object>();
        g.put("id", "g1");
        g.put("category", "top");
        g.put("name", "셔츠");
        g.put("colorName", "흰색");
        g.put("colorFamily", "white");
        g.put("tone", "light");
        g.put("pattern", null);
        g.put("material", null);
        g.put("notes", null);
        g.put("formality", "smart");
        g.put("defects", List.of(Map.of("kind", "stain", "where", "오른쪽 소매 끝", "confidence", 0.83, "checkedBy", "model")));
        g.put("box", Map.of("x", 0.1, "y", 0.2, "w", 0.3, "h", 0.4));
        g.put("position", null);
        g.put("confidence", 0.91);
        g.put("embedding", vector(3, 0.0));
        JsonNode r = call("closet_save", Map.of("userId", user, "garments", List.of(g), "embeddingModel", "marqo-fashionSigLIP"));
        assertThat(r.has("saved")).as(r.toString()).isTrue();
        assertThat(r.get("saved").get(0).get("isNew").asBoolean()).isTrue();
    }

    /** 768차원 길이 1 벡터: axis 축에 cos, 그 옆 축에 sin(angle). */
    private static List<Double> vector(int axis, double angle) {
        var v = new ArrayList<Double>();
        for (int i = 0; i < 768; i++) {
            v.add(i == axis ? Math.cos(angle) : i == axis + 100 ? Math.sin(angle) : 0.0);
        }
        return v;
    }

    @Test
    @DisplayName("같은 색 니트 두 벌: 한 번에 넘기면 두 벌, forceNewIds면 비슷해도 새 옷")
    void twoSameColorKnits() {
        var knit = garment("g1", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음");
        var knit2 = garment("g2", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음");
        JsonNode both = call("closet_save", Map.of("userId", user, "garments", List.of(knit, knit2)));
        assertThat(both.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        assertThat(both.get("saved").get(1).get("isNew").asBoolean()).isTrue();
        String a = both.get("saved").get(0).get("garmentId").asString();
        String b = both.get("saved").get(1).get("garmentId").asString();
        assertThat(a).isNotEqualTo(b);

        // 다음에 두 벌을 같이 다시 보면 각각 하나씩 합친다(한 옷에 두 벌을 몰지 않음)
        JsonNode again = call("closet_save", Map.of("userId", user, "garments", List.of(knit, knit2)));
        assertThat(List.of(again.get("saved").get(0).get("garmentId").asString(), again.get("saved").get(1).get("garmentId").asString()))
                .containsExactlyInAnyOrder(a, b);

        // 사용자가 "다른 옷이에요" → forceNewIds
        var third = garment("g3", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음");
        JsonNode forced = call("closet_save", Map.of("userId", user, "garments", List.of(third), "forceNewIds", List.of("g3")));
        assertThat(forced.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = CAST(:u AS uuid)").param("u", user)
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    @DisplayName("이상한 값은 고쳐 저장하고, 종류가 없으면 bad_request")
    void saveValidation() {
        var odd = garment("g1", "top", " ", "", "rainbow", "very", "fancy", null);
        JsonNode ok = call("closet_save", Map.of("userId", user, "garments", List.of(odd)));
        String id = ok.get("saved").get(0).get("garmentId").asString();
        var row = jdbc.sql("SELECT name, color_name, color_family, tone, formality FROM closet.garments WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row).containsEntry("name", "상의").containsEntry("color_name", "모르는 색")
                .containsEntry("color_family", "unknown").containsEntry("tone", "mid").containsEntry("formality", "unknown");

        JsonNode bad = call("closet_save", Map.of("userId", user, "garments", List.of(garment("g1", "hat", "모자", "검은색", "black", "dark", null, null))));
        assertThat(bad.get("error").get("code").asString()).isEqualTo("bad_request");
        JsonNode badUser = call("closet_save", Map.of("userId", "abc", "garments", List.of()));
        assertThat(badUser.get("error").get("code").asString()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("입은 날 기록: 같은 날 두 번은 한 번, 남의 옷이 섞이면 not_found이고 아무것도 기록 안 함")
    void wear() {
        String a = saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        String b = saveOne("bottom", "슬랙스", "남색", "navy", "dark", "formal");

        assertThat(call("closet_wear", Map.of("userId", user, "garmentIds", List.of(a, b), "date", "2026-11-04"))
                .get("recorded").asInt()).isEqualTo(2);
        assertThat(call("closet_wear", Map.of("userId", user, "garmentIds", List.of(a)))   // 날짜 없으면 오늘(서울)
                .get("recorded").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM closet.wears").query(Integer.class).single()).isEqualTo(2);

        String others = UUID.randomUUID().toString();
        JsonNode stolen = call("closet_wear", Map.of("userId", others, "garmentIds", List.of(a), "date", "2026-11-03"));
        assertThat(stolen.get("error").get("code").asString()).isEqualTo("not_found");
        JsonNode mixed = call("closet_wear", Map.of("userId", user, "garmentIds", List.of(b, "c_nope"), "date", "2026-11-03"));
        assertThat(mixed.get("error").get("code").asString()).isEqualTo("not_found");
        assertThat(jdbc.sql("SELECT count(*) FROM closet.wears").query(Integer.class).single()).isEqualTo(2);

        JsonNode future = call("closet_wear", Map.of("userId", user, "garmentIds", List.of(a), "date", "2026-12-25"));
        assertThat(future.get("error").get("code").asString()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("검색: 자리에 안 맞는 옷은 빼고, 뺄 옷 제외, 종류를 섞고, lastWorn을 붙인다")
    void search() {
        String shirt = saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        String knit = saveOne("top", "니트", "남색", "navy", "dark", "smart");
        String hoodie = saveOne("top", "후드티", "회색", "gray", "mid", "sporty");
        saveOne("top", "블라우스", "하늘색", "blue", "light", "smart");
        saveOne("top", "맨투맨", "검은색", "black", "dark", "smart");
        String slacks = saveOne("bottom", "슬랙스", "검은색", "black", "dark", "formal");
        String jacket = saveOne("outer", "자켓", "회색", "gray", "mid", "smart");
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(slacks), "date", "2026-10-28"));
        // 다른 사람 옷은 나오면 안 됨
        String other = user;
        user = UUID.randomUUID().toString();
        saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        user = other;

        JsonNode r = call("closet_search", Map.of("userId", user, "situation", "오늘 오전 면접, 체감 9도",
                "occasion", "formal", "apparentTemperature", 9.5, "excludeGarmentIds", List.of(knit), "limit", 3));
        assertThat(r.get("empty").asBoolean()).isFalse();
        var ids = new ArrayList<String>();
        r.get("candidates").forEach(c -> ids.add(c.get("garmentId").asString()));
        assertThat(ids).hasSize(3).doesNotContain(knit, hoodie);
        // 상의가 4벌 남아도 하의·겉옷이 한 벌씩 들어온다
        assertThat(ids).contains(slacks, jacket);
        JsonNode slacksOut = find(r, slacks);
        assertThat(slacksOut.get("lastWorn").asString()).isEqualTo("2026-10-28");
        assertThat(slacksOut.get("category").asString()).isEqualTo("bottom");
        assertThat(slacksOut.get("score").asDouble()).isBetween(-1.0, 1.0);

        // 더운 날에는 겉옷이 빠진다
        JsonNode hot = call("closet_search", Map.of("userId", user, "situation", "친구랑 점심, 체감 27도",
                "occasion", "casual", "apparentTemperature", 27));
        var hotIds = new ArrayList<String>();
        hot.get("candidates").forEach(c -> hotIds.add(c.get("garmentId").asString()));
        assertThat(hotIds).doesNotContain(jacket).contains(hoodie, shirt);

        JsonNode bad = call("closet_search", Map.of("userId", user, "situation", "등산", "occasion", "party"));
        assertThat(bad.get("error").get("code").asString()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("입은 조합: 같은 날 입은 옷을 묶어 최근 날부터, 옷으로 거르기, 남의 옷은 not_found")
    void outfits() {
        String shirt = saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        String slacks = saveOne("bottom", "슬랙스", "검은색", "black", "dark", "formal");
        String jacket = saveOne("outer", "자켓", "남색", "navy", "dark", "smart");
        String jeans = saveOne("bottom", "청바지", "연청", "blue", "light", "casual");
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(shirt, slacks, jacket), "date", "2026-10-28"));
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(shirt, jeans), "date", "2026-11-03"));

        JsonNode r = call("closet_outfits", Map.of("userId", user));
        assertThat(r.get("outfits").size()).isEqualTo(2);
        JsonNode latest = r.get("outfits").get(0);
        assertThat(latest.get("date").asString()).isEqualTo("2026-11-03");
        var latestIds = new ArrayList<String>();
        latest.get("garments").forEach(g -> latestIds.add(g.get("garmentId").asString()));
        assertThat(latestIds).containsExactly(shirt, jeans);   // 상의 → 하의 순
        JsonNode older = r.get("outfits").get(1);
        assertThat(older.get("garments").get(0).get("name").asString()).isEqualTo("자켓");   // 겉옷이 먼저

        JsonNode withSlacks = call("closet_outfits", Map.of("userId", user, "garmentId", slacks));
        assertThat(withSlacks.get("outfits").size()).isEqualTo(1);
        assertThat(withSlacks.get("outfits").get(0).get("garments").size()).isEqualTo(3);

        JsonNode range = call("closet_outfits", Map.of("userId", user, "from", "2026-10-01", "to", "2026-10-31"));
        assertThat(range.get("outfits").size()).isEqualTo(1);
        assertThat(call("closet_outfits", Map.of("userId", user, "limit", 1)).get("outfits").size()).isEqualTo(1);

        assertThat(call("closet_outfits", Map.of("userId", UUID.randomUUID().toString(), "garmentId", shirt))
                .get("error").get("code").asString()).isEqualTo("not_found");
        assertThat(call("closet_outfits", Map.of("userId", UUID.randomUUID().toString())).get("outfits").size()).isZero();
        assertThat(call("closet_outfits", Map.of("userId", user, "from", "2026-11-05", "to", "2026-11-01"))
                .get("error").get("code").asString()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("옷장 목록(종류 순, 입은 횟수)과 지우기(입은 날도 함께, 남의 옷 섞이면 아무것도 안 지움)")
    void listAndRemove() {
        String shirt = saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        String jeans = saveOne("bottom", "청바지", "연청", "blue", "light", "casual");
        String jacket = saveOne("outer", "자켓", "남색", "navy", "dark", "smart");
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(shirt, jeans), "date", "2026-11-01"));
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(shirt), "date", "2026-11-02"));

        JsonNode all = call("closet_list", Map.of("userId", user));
        assertThat(all.get("total").asInt()).isEqualTo(3);
        var order = new ArrayList<String>();
        all.get("garments").forEach(g -> order.add(g.get("garmentId").asString()));
        assertThat(order).containsExactly(jacket, shirt, jeans);   // 겉옷 → 상의 → 하의
        assertThat(all.get("garments").get(1).get("wearCount").asInt()).isEqualTo(2);
        assertThat(all.get("garments").get(1).get("lastWorn").asString()).isEqualTo("2026-11-02");

        JsonNode bottoms = call("closet_list", Map.of("userId", user, "category", "bottom"));
        assertThat(bottoms.get("total").asInt()).isEqualTo(1);
        assertThat(call("closet_list", Map.of("userId", user, "category", "hat")).get("error").get("code").asString())
                .isEqualTo("bad_request");

        // 남의 옷이 섞이면 아무것도 지우지 않음
        String other = UUID.randomUUID().toString();
        String othersShirt = call("closet_save", Map.of("userId", other, "garments", List.of(
                garment("g1", "top", "셔츠", "흰색", "white", "light", "smart", null)))).get("saved").get(0).get("garmentId").asString();
        assertThat(call("closet_remove", Map.of("userId", user, "garmentIds", List.of(jeans, othersShirt)))
                .get("error").get("code").asString()).isEqualTo("not_found");
        assertThat(call("closet_list", Map.of("userId", user)).get("total").asInt()).isEqualTo(3);

        assertThat(call("closet_remove", Map.of("userId", user, "garmentIds", List.of(jeans))).get("removed").asInt()).isEqualTo(1);
        assertThat(call("closet_list", Map.of("userId", user)).get("total").asInt()).isEqualTo(2);
        // 입은 날 기록에서도 빠짐
        JsonNode outfit = call("closet_outfits", Map.of("userId", user, "from", "2026-11-01", "to", "2026-11-01"));
        assertThat(outfit.get("outfits").get(0).get("garments").size()).isEqualTo(1);
        assertThat(call("closet_list", Map.of("userId", other)).get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("어제 입은 옷은 점수가 낮아져 같은 종류 안에서 뒤로 간다")
    void recentlyWornGoesDown() {
        // hash 임베딩에서 같은 글이면 같은 점수라, 같은 설명의 셔츠 두 벌(색 이름만 다름)로 비교한다
        String a = saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        String b = saveOne("top", "셔츠", "흰색 ", "gray", "light", "smart");
        JsonNode before = call("closet_search", Map.of("userId", user, "situation", "출근", "limit", 2));
        String first = before.get("candidates").get(0).get("garmentId").asString();
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(first), "date", "2026-11-03"));   // 어제(서울 11-04 기준)
        JsonNode after = call("closet_search", Map.of("userId", user, "situation", "출근", "limit", 2));
        assertThat(after.get("candidates").get(0).get("garmentId").asString()).isNotEqualTo(first).isIn(a, b);
        assertThat(find(after, first).get("lastWorn").asString()).isEqualTo("2026-11-03");
    }

    private JsonNode find(JsonNode r, String id) {
        for (JsonNode c : r.get("candidates")) {
            if (c.get("garmentId").asString().equals(id)) {
                return c;
            }
        }
        throw new AssertionError(id + " 없음");
    }

    private String saveOne(String category, String name, String colorName, String family, String tone, String formality) {
        JsonNode r = call("closet_save", Map.of("userId", user, "garments",
                List.of(garment("g1", category, name, colorName, family, tone, formality, null))));
        assertThat(r.get("saved").get(0).get("isNew").asBoolean()).as(name + " 새 옷").isTrue();
        return r.get("saved").get(0).get("garmentId").asString();
    }

    private static Map<String, Object> garment(String id, String category, String name, String colorName, String family,
                                               String tone, String formality, String material) {
        var g = new LinkedHashMap<String, Object>();
        g.put("id", id);
        g.put("category", category);
        g.put("name", name);
        g.put("colorName", colorName);
        g.put("colorFamily", family);
        g.put("tone", tone);
        if (formality != null) {
            g.put("formality", formality);
        }
        if (material != null) {
            g.put("material", material);
        }
        g.put("position", "center");
        g.put("confidence", 0.9);
        return g;
    }

    private JsonNode call(String tool, Map<String, Object> args) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return json.readTree(text);
    }
}
