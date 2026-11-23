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
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 테스트: 서버 전체와 진짜 PostgreSQL(pgvector)을 띄우고 MCP 클라이언트로 /mcp에 붙어 본다(agent가 붙는 방식과 같음).
 * 임베딩은 모델 없이 도는 hash를 쓴다(뜻은 모르지만 같은 글은 같은 벡터).
 * 앞쪽 "US…" 테스트는 앱 레포 specs/003-closet/spec.md(와 005-database US1·US2)의 Given/When/Then 시나리오 그대로이고,
 * 뒤쪽은 같은 옷 판단·검색 규칙을 더 자세히 확인한다. DB가 없으면(TestDb#available) 건너뛴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"closet.embedding.provider=hash", "closet.image.adapter-path="})
@Import(TestDb.class)
@EnabledIf("com.fashionagent.closet.TestDb#available")
class ClosetIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

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

    // ---- spec 시나리오 (도구 입력은 Map 대신 record를 JSON으로 바꿔 보냄) ----

    record SaveArgs(String userId, List<Garment> garments) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record SearchArgs(String userId, String situation, String occasion, Double apparentTemperature,
            List<String> excludeGarmentIds, Integer limit) {
    }

    record WearArgs(String userId, List<String> garmentIds, String date) {
    }

    static Garment seen(String id, String category, String name, String colorName, String family, String tone,
            String formality) {
        return new Garment(id, category, name, colorName, family, tone, null, null, null, formality, null, null, "center",
                0.9, null, null, null, null, null, null);
    }

    JsonNode callArgs(String tool, Object args) {
        Map<String, Object> map = json.convertValue(args, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
        return call(tool, map);
    }

    String save(Garment g) {
        return callArgs("closet_save", new SaveArgs(user, List.of(g))).get("saved").get(0).get("garmentId").asString();
    }

    @Test
    @DisplayName("US1-1 Given 처음 보는 옷 When 마무리하면 Then 새 옷으로 저장되고 isNew=true")
    void us1_1_newGarment() {
        // when
        JsonNode r = callArgs("closet_save", new SaveArgs(user, List.of(seen("g1", "top", "니트", "차콜 그레이", "gray", "dark", "smart"))));

        // then
        assertThat(r.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = ?::uuid").param(user).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("US1-2 Given 전에 저장한 셔츠와 종류·색이 같고 매우 비슷함 When 마무리하면 Then 기존 옷에 합치고 마지막으로 본 시각만 바뀐다")
    void us1_2_sameGarmentMerges() {
        // given
        String shirt = save(seen("g1", "top", "셔츠", "흰색", "white", "light", "smart"));
        jdbc.sql("UPDATE closet.garments SET first_seen_at = '2026-11-01T05:00:00Z', last_seen_at = '2026-11-01T05:00:00Z' WHERE id = ?")
                .param(shirt).update();

        // when
        JsonNode r = callArgs("closet_save", new SaveArgs(user, List.of(seen("g9", "top", "셔츠", "흰색", "white", "light", "smart"))));

        // then
        assertThat(r.get("saved").get(0).get("garmentId").asString()).isEqualTo(shirt);
        assertThat(r.get("saved").get(0).get("isNew").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = ?::uuid").param(user).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_seen_at > first_seen_at FROM closet.garments WHERE id = ?").param(shirt)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("US1-3 Given 같은 색 다른 종류(셔츠와 니트) When 마무리하면 Then 합치지 않는다")
    void us1_3_differentCategoryNotMerged() {
        // given
        String shirt = save(seen("g1", "top", "셔츠", "회색", "gray", "mid", "smart"));

        // when
        JsonNode r = callArgs("closet_save", new SaveArgs(user, List.of(seen("g2", "top", "니트", "회색", "gray", "mid", "smart"))));

        // then
        assertThat(r.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        assertThat(r.get("saved").get(0).get("garmentId").asString()).isNotEqualTo(shirt);
    }

    @Test
    @DisplayName("US2-1·US2-2·005 US2-2 Given 옷 두 벌 When 오늘 입었다고 기록하고 한 번 더 기록하면 Then 두 건만 남는다(하루 한 번)")
    void us2_wearOncePerDay() {
        // given
        String top = save(seen("g1", "top", "셔츠", "흰색", "white", "light", "smart"));
        String bottom = save(seen("g2", "bottom", "슬랙스", "검은색", "black", "dark", "formal"));

        // when
        callArgs("closet_wear", new WearArgs(user, List.of(top, bottom), "2026-11-04"));
        callArgs("closet_wear", new WearArgs(user, List.of(top), "2026-11-04"));

        // then
        assertThat(jdbc.sql("SELECT count(*) FROM closet.wears WHERE garment_id IN (?, ?)").params(top, bottom)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("US3-1 Given 옷장에 옷이 있음 When 상황·자리·체감 온도로 찾으면 Then 정한 수만큼 후보와 마지막으로 입은 날을 받는다")
    void us3_1_searchWithLastWorn() {
        // given
        String shirt = save(seen("g1", "top", "셔츠", "흰색", "white", "light", "formal"));
        save(seen("g2", "bottom", "슬랙스", "검은색", "black", "dark", "formal"));
        save(seen("g3", "top", "후드티", "회색", "gray", "mid", "casual"));
        callArgs("closet_wear", new WearArgs(user, List.of(shirt), "2026-11-01"));

        // when
        JsonNode r = callArgs("closet_search", new SearchArgs(user, "오늘 오전 면접, 체감 9도", "formal", 9.5, null, 2));

        // then
        assertThat(r.get("empty").asBoolean()).isFalse();
        assertThat(r.get("candidates").size()).isEqualTo(2);
        assertThat(find(r, shirt).get("lastWorn").asString()).isEqualTo("2026-11-01");
    }

    @Test
    @DisplayName("US3-2 Given 빼고 싶은 옷 목록 When 찾으면 Then 그 옷은 후보에 없다")
    void us3_2_excluded() {
        // given
        String yesterday = save(seen("g1", "top", "셔츠", "흰색", "white", "light", "smart"));
        save(seen("g2", "top", "니트", "남색", "navy", "dark", "smart"));

        // when
        JsonNode r = callArgs("closet_search", new SearchArgs(user, "출근", "business", null, List.of(yesterday), null));

        // then
        List<String> ids = new ArrayList<>();
        r.get("candidates").forEach(c -> ids.add(c.get("garmentId").asString()));
        assertThat(ids).isNotEmpty().doesNotContain(yesterday);
    }

    @Test
    @DisplayName("US3-3 Given 옷장이 비어 있음 When 찾으면 Then 빈 후보와 코디 지식만 받고 empty=true")
    void us3_3_emptyCloset() {
        // when
        JsonNode r = callArgs("closet_search", new SearchArgs(user, "오늘 오전 면접, 체감 9도", "formal", 9.5, null, null));

        // then
        assertThat(r.get("empty").asBoolean()).isTrue();
        assertThat(r.get("candidates").size()).isZero();
        assertThat(r.get("styleNotes").size()).isPositive();
    }

    @Test
    @DisplayName("005 US2-3 Given 사용자 A·B에게 같은 셔츠 When A로 찾으면 Then B의 옷은 0건")
    void db_us2_3_otherUserIsolated() {
        // given
        String mine = save(seen("g1", "top", "셔츠", "흰색", "white", "light", "smart"));
        String me = user;
        user = UUID.randomUUID().toString();
        String theirs = save(seen("g1", "top", "셔츠", "흰색", "white", "light", "smart"));
        user = me;

        // when
        JsonNode r = callArgs("closet_search", new SearchArgs(user, "출근", "business", null, null, null));

        // then
        List<String> ids = new ArrayList<>();
        r.get("candidates").forEach(c -> ids.add(c.get("garmentId").asString()));
        assertThat(ids).contains(mine).doesNotContain(theirs);
    }

    @Test
    @DisplayName("005 US1-2 Given Flyway가 closet 스키마를 만듦 When 외래 키를 보면 Then 다른 스키마를 가리키는 FK는 0개")
    void db_us1_2_noCrossSchemaFk() {
        // when
        Long crossSchema = jdbc.sql("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                JOIN pg_class ref ON ref.oid = c.confrelid
                WHERE c.contype = 'f' AND n.nspname = 'closet' AND ref.relnamespace <> c.connamespace""")
                .query(Long.class).single();

        // then
        assertThat(crossSchema).isZero();
    }

    // ---- 같은 옷 판단·검색 규칙 자세히 ----

    @Test
    @DisplayName("tools list에 closet 도구 4개가 있고 필수 입력이 맞다")
    @SuppressWarnings("unchecked")
    void toolsList() {
        var tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("closet_search", "closet_save", "closet_wear", "closet_outfits", "closet_list", "closet_remove", "closet_merge");
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
        // 얼룩은 garment_defects에 한 줄씩(V4 정규화)
        assertThat(jdbc.sql("SELECT kind || '/' || location || '/' || confidence FROM closet.garment_defects WHERE garment_id = :id")
                .param("id", knit).query(String.class).list()).containsExactly("stain/오른쪽 소매 끝/0.850");
        assertThat(jdbc.sql("SELECT material FROM closet.garments WHERE id = :id").param("id", knit)
                .query(String.class).single()).isEqualTo("니트 같음");
        // 다음에 얼룩 없이 다시 보면 얼룩 줄도 마지막 상태(없음)로 바뀐다
        call("closet_save", Map.of("userId", user, "garments", List.of(
                garment("g9", "top", "니트", "차콜 그레이", "gray", "dark", "smart", "니트 같음"))));
        assertThat(jdbc.sql("SELECT count(*) FROM closet.garment_defects WHERE garment_id = :id").param("id", knit)
                .query(Integer.class).single()).isZero();

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
    @DisplayName("사진이 애매하게 비슷하면 새 옷으로 저장하고 maybeSameAs(예전 작은 사진 포함)를 주며, closet_merge로 합친다")
    void maybeSameAndMerge() {
        String jpeg = java.util.Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3});
        var first = garment("g1", "outer", "코트", "검정", "black", "dark", "smart", "울");
        first.put("embedding", vector(5, 0.0));
        first.put("thumbnail", jpeg);
        JsonNode r1 = call("closet_save", Map.of("userId", user, "garments", List.of(first), "embeddingModel", "marqo-fashionSigLIP"));
        String coat = r1.get("saved").get(0).get("garmentId").asString();
        assertThat(r1.get("saved").get(0).has("maybeSameAs")).isFalse();
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(coat), "date", "2026-11-01"));

        // 보정 없음(테스트 설정): 기준 0.89, 애매함 0.79~0.89. 코사인 0.84
        var again = garment("g1", "outer", "코트", "차콜", "gray", "dark", "smart", "울");
        again.put("embedding", vector(5, Math.acos(0.84)));
        JsonNode r2 = call("closet_save", Map.of("userId", user, "garments", List.of(again), "embeddingModel", "marqo-fashionSigLIP"));
        JsonNode s2 = r2.get("saved").get(0);
        assertThat(s2.get("isNew").asBoolean()).isTrue();
        assertThat(s2.get("maybeSameAs").get("garmentId").asString()).isEqualTo(coat);
        assertThat(s2.get("maybeSameAs").get("thumbnail").asString()).isEqualTo(jpeg);
        assertThat(s2.get("maybeSameAs").get("similarity").asDouble()).isBetween(0.83, 0.85);
        String dup = s2.get("garmentId").asString();

        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(dup), "date", "2026-11-02"));
        JsonNode m = call("closet_merge", Map.of("userId", user, "garmentId", dup, "intoGarmentId", coat));
        assertThat(m.get("into").asString()).isEqualTo(coat);
        assertThat(jdbc.sql("SELECT count(*) FROM closet.garments WHERE id = :id").param("id", dup).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT image_count FROM closet.garments WHERE id = :id").param("id", coat).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM closet.wears WHERE garment_id = :id").param("id", coat).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT color_name FROM closet.garments WHERE id = :id").param("id", coat).query(String.class).single())
                .isEqualTo("차콜");

        // 사진 벡터가 없으면(인식 실패) 글로 가장 가까운 같은 종류·색 옷을 maybeSameAs로 준다(작은 사진이 있을 때)
        var noVec = garment("g1", "outer", "점퍼", "검정", "black", "dark", "casual", "나일론");
        noVec.put("thumbnail", jpeg);
        JsonNode r3 = call("closet_save", Map.of("userId", user, "garments", List.of(noVec)));
        assertThat(r3.get("saved").get(0).get("isNew").asBoolean()).isTrue();
        assertThat(r3.get("saved").get(0).get("maybeSameAs").get("garmentId").asString()).isEqualTo(coat);

        // 종류가 다르면 합치지 않는다
        var pants = garment("g1", "bottom", "슬랙스", "검정", "black", "dark", "smart", "울");
        String p = call("closet_save", Map.of("userId", user, "garments", List.of(pants))).get("saved").get(0).get("garmentId").asString();
        assertThat(call("closet_merge", Map.of("userId", user, "garmentId", p, "intoGarmentId", coat)).get("error").get("code").asString())
                .isEqualTo("bad_request");
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
        // 2026-11-12 agent #34·#35가 넘기기 시작한 필드
        g.put("subColorName", "남색");
        g.put("subColorFamily", "navy");
        g.put("patternCheckedBy", "model");
        g.put("patternGuess", "줄무늬");
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

        // 테스트 시계는 2026-11-04. 366일 뒤(2027-11-05)까지 받고 367일 뒤는 잘못 들은 날짜로 본다.
        JsonNode tooFar = call("closet_wear", Map.of("userId", user, "garmentIds", List.of(a), "date", "2027-11-06"));
        assertThat(tooFar.get("error").get("code").asString()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("앞날 코디 기록: 모레·12월 25일로 고른 옷도 기록되고, 아직 안 입었으니 lastWorn·입은 횟수·최근 감점엔 안 센다")
    void wearPlannedDay() {
        // given
        String shirt = saveOne("top", "셔츠", "흰색", "white", "light", "smart");
        String slacks = saveOne("bottom", "슬랙스", "남색", "navy", "dark", "formal");
        call("closet_wear", Map.of("userId", user, "garmentIds", List.of(shirt), "date", "2026-11-01"));

        // when
        JsonNode dayAfter = call("closet_wear", Map.of("userId", user, "garmentIds", List.of(shirt, slacks), "date", "2026-11-06"));
        JsonNode christmas = call("closet_wear", Map.of("userId", user, "garmentIds", List.of(slacks), "date", "2026-12-25"));
        JsonNode lastDay = call("closet_wear", Map.of("userId", user, "garmentIds", List.of(slacks), "date", "2027-11-05"));

        // then
        assertThat(dayAfter.get("recorded").asInt()).isEqualTo(2);
        assertThat(christmas.get("recorded").asInt()).isEqualTo(1);
        assertThat(lastDay.get("recorded").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM closet.wears").query(Integer.class).single()).isEqualTo(5);

        JsonNode list = call("closet_list", Map.of("userId", user));
        JsonNode shirtOut = findIn(list, "garments", shirt);
        JsonNode slacksOut = findIn(list, "garments", slacks);
        assertThat(shirtOut.get("lastWorn").asString()).isEqualTo("2026-11-01");
        assertThat(shirtOut.get("wearCount").asInt()).isEqualTo(1);
        assertThat(slacksOut.path("lastWorn").isMissingNode() || slacksOut.path("lastWorn").isNull()).isTrue();
        assertThat(slacksOut.get("wearCount").asInt()).isZero();

        JsonNode search = call("closet_search", Map.of("userId", user, "situation", "회사 출근, 체감 15도", "occasion", "business", "limit", 10));
        JsonNode slacksCand = find(search, slacks);
        assertThat(slacksCand.path("lastWorn").isMissingNode() || slacksCand.path("lastWorn").isNull()).isTrue();
        assertThat(find(search, shirt).get("lastWorn").asString()).isEqualTo("2026-11-01");
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
        return findIn(r, "candidates", id);
    }

    private JsonNode findIn(JsonNode r, String field, String id) {
        for (JsonNode c : r.get(field)) {
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
