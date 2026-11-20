package com.fashionagent.closet;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** closet.garments, closet.garment_defects, closet.wears (db/migration V1~V4). */
@Repository
class GarmentRepository {

    private final JdbcClient jdbc;

    GarmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** pattern은 clothes_inspect가 준 무늬(없으면 null). */
    record Match(String id, String name, String tone, String pattern, double similarity) {
    }

    /** 같은 옷 후보: 내 옷 중 종류·색 계열이 같은 것을 가까운 순으로 몇 개. */
    List<Match> nearestSame(UUID userId, String category, String colorFamily, float[] q, int limit) {
        return jdbc.sql("""
                        SELECT id, name, tone, pattern, 1 - (embedding <=> CAST(:q AS public.vector)) AS similarity FROM closet.garments
                         WHERE user_id = :userId AND category = :category AND color_family = :colorFamily AND embedding IS NOT NULL
                         ORDER BY embedding <=> CAST(:q AS public.vector) LIMIT :limit""")
                .param("q", Vectors.literal(q)).param("userId", userId)
                .param("category", category).param("colorFamily", colorFamily).param("limit", limit)
                .query((rs, i) -> new Match(rs.getString("id"), rs.getString("name"), rs.getString("tone"),
                        rs.getString("pattern"), rs.getDouble("similarity")))
                .list();
    }

    /** 사진 벡터가 있는 내 옷(같은 종류, 같은 벡터 모델). 비교는 {@link ImageMatcher}가 자바에서 한다(보정 행렬 때문). */
    record ImageRow(String id, float[] embedding, int count) {
    }

    List<ImageRow> imageRows(UUID userId, String category, String model) {
        return jdbc.sql("""
                        SELECT id, image_embedding::text AS v, image_count FROM closet.garments
                         WHERE user_id = :userId AND category = :category AND image_model = :model AND image_embedding IS NOT NULL""")
                .param("userId", userId).param("category", category).param("model", model)
                .query((rs, i) -> new ImageRow(rs.getString("id"), Vectors.parse(rs.getString("v")), rs.getInt("image_count")))
                .list();
    }

    /** 사진 벡터(평균)를 바꾼다. insert·update 다음에 부른다. */
    void setImage(String id, UUID userId, String model, float[] embedding, int count) {
        jdbc.sql("""
                        UPDATE closet.garments SET image_embedding = CAST(:v AS public.vector), image_model = :model, image_count = :count
                         WHERE id = :id AND user_id = :userId""")
                .param("v", Vectors.literal(embedding)).param("model", model).param("count", count)
                .param("id", id).param("userId", userId)
                .update();
    }

    int countByUser(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = :userId").param("userId", userId)
                .query(Integer.class).single();
    }

    /** description(jsonb) 대신 칸으로 나눈 clothes_inspect 값(V4). */
    record Details(String pattern, String patternCheckedBy, String patternGuess, String material, String notes,
                   String subColorName, String subColorFamily, List<DefectRow> defects) {
    }

    record DefectRow(String kind, String location, Double confidence, String checkedBy) {
    }

    void insert(String id, UUID userId, Garment.Normalized g, Details d, float[] embedding, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO closet.garments (id, user_id, category, name, color_name, color_family, tone, formality,
                                              pattern, pattern_checked_by, pattern_guess, material, notes, sub_color_name, sub_color_family,
                                              embedding, first_seen_at, last_seen_at, created_at, updated_at)
                        VALUES (:id, :userId, :category, :name, :colorName, :colorFamily, :tone, :formality,
                                :pattern, :patternCheckedBy, :patternGuess, :material, :notes, :subColorName, :subColorFamily,
                                CAST(:embedding AS public.vector), :now, :now, :now, :now)""")
                .param("id", id).param("userId", userId)
                .param("category", g.category()).param("name", g.name()).param("colorName", g.colorName())
                .param("colorFamily", g.colorFamily()).param("tone", g.tone()).param("formality", g.formality())
                .params(detailParams(d))
                .param("embedding", embedding == null ? null : Vectors.literal(embedding))
                .param("now", now)
                .update();
        replaceDefects(id, d.defects());
    }

    /** 같은 옷을 다시 봤을 때: 마지막으로 본 설명으로 바꾼다(종류·색 계열은 같으므로 그대로). */
    void update(String id, UUID userId, Garment.Normalized g, Details d, float[] embedding, OffsetDateTime now) {
        int n = jdbc.sql("""
                        UPDATE closet.garments SET name = :name, color_name = :colorName, tone = :tone,
                               formality = CASE WHEN :formality = 'unknown' THEN formality ELSE :formality END,
                               pattern = :pattern, pattern_checked_by = :patternCheckedBy, pattern_guess = :patternGuess,
                               material = :material, notes = :notes, sub_color_name = :subColorName, sub_color_family = :subColorFamily,
                               embedding = COALESCE(CAST(:embedding AS public.vector), embedding),
                               last_seen_at = GREATEST(last_seen_at, :now), updated_at = :now
                         WHERE id = :id AND user_id = :userId""")
                .param("id", id).param("userId", userId)
                .param("name", g.name()).param("colorName", g.colorName()).param("tone", g.tone())
                .param("formality", g.formality()).params(detailParams(d))
                .param("embedding", embedding == null ? null : Vectors.literal(embedding)).param("now", now)
                .update();
        if (n > 0) {
            replaceDefects(id, d.defects());
        }
    }

    private static Map<String, Object> detailParams(Details d) {
        var m = new java.util.HashMap<String, Object>();
        m.put("pattern", d.pattern());
        m.put("patternCheckedBy", d.patternCheckedBy());
        m.put("patternGuess", d.patternGuess());
        m.put("material", d.material());
        m.put("notes", d.notes());
        m.put("subColorName", d.subColorName());
        m.put("subColorFamily", d.subColorFamily());
        return m;
    }

    /** 얼룩·찢어짐은 마지막으로 본 상태로 바꾼다. */
    private void replaceDefects(String garmentId, List<DefectRow> defects) {
        jdbc.sql("DELETE FROM closet.garment_defects WHERE garment_id = :id").param("id", garmentId).update();
        for (DefectRow r : defects) {
            jdbc.sql("""
                            INSERT INTO closet.garment_defects (garment_id, kind, location, confidence, checked_by)
                            VALUES (:id, :kind, :location, :confidence, :checkedBy)""")
                    .param("id", garmentId).param("kind", r.kind()).param("location", r.location())
                    .param("confidence", r.confidence()).param("checkedBy", r.checkedBy())
                    .update();
        }
    }

    /** 옷의 얼룩·찢어짐(테스트·확인용). */
    List<DefectRow> defects(String garmentId) {
        return jdbc.sql("SELECT kind, location, confidence, checked_by FROM closet.garment_defects WHERE garment_id = :id ORDER BY id")
                .param("id", garmentId)
                .query((rs, i) -> new DefectRow(rs.getString(1), rs.getString(2),
                        rs.getObject(3) == null ? null : rs.getDouble(3), rs.getString(4)))
                .list();
    }

    /** 이 사람 옷 중 있는 id만. */
    List<String> ownedIds(UUID userId, Collection<String> ids) {
        return jdbc.sql("SELECT id FROM closet.garments WHERE user_id = :userId AND id IN (:ids)")
                .param("userId", userId).param("ids", ids).query(String.class).list();
    }

    void recordWear(UUID userId, String garmentId, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO closet.wears (garment_id, worn_on)
                        SELECT id, :day FROM closet.garments WHERE id = :garmentId AND user_id = :userId
                        ON CONFLICT ON CONSTRAINT wears_garment_day_uq DO NOTHING""")
                .param("userId", userId).param("garmentId", garmentId).param("day", day)
                .update();
    }

    /** 입은 날 목록(최근부터 limit개). garmentId가 있으면 그 옷을 입은 날만. */
    List<LocalDate> wornDays(UUID userId, LocalDate from, LocalDate to, String garmentId, int limit) {
        String byGarment = garmentId == null ? "" : " AND w.garment_id = :garmentId";
        var spec = jdbc.sql("SELECT DISTINCT w.worn_on FROM closet.wears w JOIN closet.garments g ON g.id = w.garment_id"
                        + " WHERE g.user_id = :userId AND w.worn_on BETWEEN :from AND :to"
                        + byGarment + " ORDER BY w.worn_on DESC LIMIT :limit")
                .param("userId", userId).param("from", from).param("to", to).param("limit", limit);
        if (garmentId != null) {
            spec = spec.param("garmentId", garmentId);
        }
        return spec.query(LocalDate.class).list();
    }

    record WornRow(LocalDate day, String id, String category, String name, String colorName, String formality) {
    }

    /** 그 날들에 입은 옷. 날은 최근부터, 한 날 안에서는 겉옷·상의·원피스·하의·신발·기타 순. */
    List<WornRow> wornOn(UUID userId, Collection<LocalDate> days) {
        return jdbc.sql("""
                        SELECT w.worn_on, g.id, g.category, g.name, g.color_name, g.formality
                          FROM closet.wears w JOIN closet.garments g ON g.id = w.garment_id
                         WHERE g.user_id = :userId AND w.worn_on IN (:days)
                         ORDER BY w.worn_on DESC, array_position(ARRAY['outer','top','dress','bottom','shoes','other']::varchar[], g.category), g.id""")
                .param("userId", userId).param("days", days)
                .query((rs, i) -> new WornRow(rs.getObject("worn_on", LocalDate.class), rs.getString("id"), rs.getString("category"),
                        rs.getString("name"), rs.getString("color_name"), rs.getString("formality")))
                .list();
    }

    record Candidate(String id, String category, String name, String colorName, String colorFamily, String tone,
                     String formality, String material, LocalDate lastWorn, double similarity) {

        Candidate withSimilarity(double s) {
            return new Candidate(id, category, name, colorName, colorFamily, tone, formality, material, lastWorn, s);
        }
    }

    /** 사진 벡터가 있는 내 옷마다 영어 검색어 벡터와의 코사인 유사도(같은 모델 벡터만). */
    Map<String, Double> imageSimilarities(UUID userId, float[] q, String model) {
        var out = new java.util.HashMap<String, Double>();
        jdbc.sql("""
                        SELECT id, 1 - (image_embedding <=> CAST(:q AS public.vector)) AS s FROM closet.garments
                         WHERE user_id = :userId AND image_model = :model AND image_embedding IS NOT NULL""")
                .param("q", Vectors.literal(q)).param("userId", userId).param("model", model)
                .query((rs, i) -> out.put(rs.getString("id"), rs.getDouble("s"))).list();
        return out;
    }

    /** 내 옷 전부를 질문과 가까운 순으로(한 사람 100벌 안팎이라 정확한 거리로 다 훑는다, research R3). */
    List<Candidate> rankedForUser(UUID userId, float[] q, Collection<String> excludeIds) {
        String exclude = excludeIds.isEmpty() ? "" : " AND g.id NOT IN (:exclude)";
        var spec = jdbc.sql("""
                        SELECT g.id, g.category, g.name, g.color_name, g.color_family, g.tone, g.formality, g.material,
                               (SELECT max(w.worn_on) FROM closet.wears w WHERE w.garment_id = g.id) AS last_worn_on,
                               1 - (g.embedding <=> CAST(:q AS public.vector)) AS similarity
                          FROM closet.garments g
                         WHERE g.user_id = :userId AND g.embedding IS NOT NULL""" + exclude + """

                         ORDER BY g.embedding <=> CAST(:q AS public.vector)""")
                .param("q", Vectors.literal(q)).param("userId", userId);
        if (!excludeIds.isEmpty()) {
            spec = spec.param("exclude", excludeIds);
        }
        return spec.query((rs, i) -> new Candidate(rs.getString("id"), rs.getString("category"), rs.getString("name"),
                        rs.getString("color_name"), rs.getString("color_family"), rs.getString("tone"), rs.getString("formality"),
                        rs.getString("material"), rs.getObject("last_worn_on", LocalDate.class), rs.getDouble("similarity")))
                .list();
    }

    record Listed(String garmentId, String category, String name, String colorName, String formality, LocalDate lastWorn,
                  int wearCount) {
    }

    /** 옷장 목록: 종류 순(겉옷·상의·원피스·하의·신발·기타), 같은 종류는 최근에 본 것부터. */
    List<Listed> listForUser(UUID userId, String category, int limit) {
        String cat = category == null ? "" : " AND g.category = :category";
        var spec = jdbc.sql("""
                        SELECT g.id, g.category, g.name, g.color_name, g.formality,
                               max(w.worn_on) AS last_worn_on, count(w.worn_on) AS wear_count
                          FROM closet.garments g LEFT JOIN closet.wears w ON w.garment_id = g.id
                         WHERE g.user_id = :userId""" + cat + " " + """
                         GROUP BY g.id
                         ORDER BY array_position(ARRAY['outer','top','dress','bottom','shoes','other']::varchar[], g.category),
                                  g.last_seen_at DESC, g.id
                         LIMIT :limit""")
                .param("userId", userId).param("limit", limit);
        if (category != null) {
            spec = spec.param("category", category);
        }
        return spec.query((rs, i) -> new Listed(rs.getString("id"), rs.getString("category"), rs.getString("name"),
                rs.getString("color_name"), rs.getString("formality"), rs.getObject("last_worn_on", LocalDate.class),
                rs.getInt("wear_count"))).list();
    }

    int countByUser(UUID userId, String category) {
        if (category == null) {
            return countByUser(userId);
        }
        return jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = :userId AND category = :category")
                .param("userId", userId).param("category", category).query(Integer.class).single();
    }

    record Brief(String id, String name, String colorName, String pattern, String material, String thumbnail) {
    }

    /** 작은 사진을 최근 것으로 바꾼다. null이면 그대로 둔다. */
    void setThumbnail(String id, UUID userId, byte[] jpeg, OffsetDateTime now) {
        if (jpeg == null) {
            return;
        }
        jdbc.sql("UPDATE closet.garments SET thumbnail = :t, thumbnail_at = :now WHERE id = :id AND user_id = :userId")
                .param("t", jpeg).param("now", now).param("id", id).param("userId", userId).update();
    }

    java.util.Optional<Brief> brief(UUID userId, String id) {
        return jdbc.sql("""
                        SELECT id, name, color_name, pattern, material,
                               thumbnail
                          FROM closet.garments WHERE user_id = :userId AND id = :id""")
                .param("userId", userId).param("id", id)
                .query((rs, i) -> new Brief(rs.getString("id"), rs.getString("name"), rs.getString("color_name"),
                        rs.getString("pattern"), rs.getString("material"),
                        rs.getBytes("thumbnail") == null ? null : java.util.Base64.getEncoder().encodeToString(rs.getBytes("thumbnail"))))
                .optional();
    }

    record MergeRow(String id, String category, float[] image, String model, int count) {
    }

    java.util.Optional<MergeRow> mergeRow(UUID userId, String id) {
        return jdbc.sql("""
                        SELECT id, category, image_embedding::text AS v, image_model, image_count
                          FROM closet.garments WHERE user_id = :userId AND id = :id""")
                .param("userId", userId).param("id", id)
                .query((rs, i) -> new MergeRow(rs.getString("id"), rs.getString("category"),
                        rs.getString("v") == null ? null : Vectors.parse(rs.getString("v")), rs.getString("image_model"),
                        rs.getInt("image_count")))
                .optional();
    }

    /**
     * from을 into에 합친다(사진 벡터는 부르는 쪽이 먼저 정함): 입은 날을 옮기고(같은 날은 하나만), 설명·글 임베딩은 더 최근에 본 쪽(같으면 from),
     * 처음 본 날은 더 이른 쪽으로 둔 뒤 from을 지운다.
     */
    void mergeInto(UUID userId, String from, String into) {
        jdbc.sql("""
                        UPDATE closet.wears SET garment_id = :into, updated_at = now()
                         WHERE garment_id = :from
                           AND EXISTS (SELECT 1 FROM closet.garments g WHERE g.id = :from AND g.user_id = :userId)
                           AND worn_on NOT IN (SELECT worn_on FROM closet.wears WHERE garment_id = :into)""")
                .param("userId", userId).param("from", from).param("into", into).update();
        // 얼룩·찢어짐도 설명처럼 더 최근에 본 쪽을 남긴다(garments를 고치기 전에 last_seen_at으로 가른다)
        jdbc.sql("""
                        WITH newer AS (
                          SELECT 1 FROM closet.garments f, closet.garments t
                           WHERE f.id = :from AND t.id = :into AND f.user_id = :userId AND t.user_id = :userId
                             AND f.last_seen_at >= t.last_seen_at),
                        gone AS (
                          DELETE FROM closet.garment_defects WHERE garment_id = :into AND EXISTS (SELECT 1 FROM newer))
                        UPDATE closet.garment_defects SET garment_id = :into
                         WHERE garment_id = :from AND EXISTS (SELECT 1 FROM newer)""")
                .param("userId", userId).param("from", from).param("into", into).update();
        jdbc.sql("""
                        UPDATE closet.garments t SET
                               name = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.name ELSE t.name END,
                               color_name = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.color_name ELSE t.color_name END,
                               tone = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.tone ELSE t.tone END,
                               formality = CASE WHEN f.last_seen_at >= t.last_seen_at AND f.formality <> 'unknown' THEN f.formality ELSE t.formality END,
                               pattern = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.pattern ELSE t.pattern END,
                               pattern_checked_by = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.pattern_checked_by ELSE t.pattern_checked_by END,
                               pattern_guess = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.pattern_guess ELSE t.pattern_guess END,
                               material = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.material ELSE t.material END,
                               notes = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.notes ELSE t.notes END,
                               sub_color_name = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.sub_color_name ELSE t.sub_color_name END,
                               sub_color_family = CASE WHEN f.last_seen_at >= t.last_seen_at THEN f.sub_color_family ELSE t.sub_color_family END,
                               embedding = CASE WHEN f.last_seen_at >= t.last_seen_at THEN COALESCE(f.embedding, t.embedding) ELSE t.embedding END,
                               thumbnail = CASE WHEN f.thumbnail IS NOT NULL AND (t.thumbnail IS NULL OR f.thumbnail_at >= t.thumbnail_at)
                                                THEN f.thumbnail ELSE t.thumbnail END,
                               thumbnail_at = CASE WHEN f.thumbnail IS NOT NULL AND (t.thumbnail IS NULL OR f.thumbnail_at >= t.thumbnail_at)
                                                THEN f.thumbnail_at ELSE t.thumbnail_at END,
                               first_seen_at = LEAST(f.first_seen_at, t.first_seen_at),
                               last_seen_at = GREATEST(f.last_seen_at, t.last_seen_at), updated_at = now()
                          FROM closet.garments f
                         WHERE t.id = :into AND t.user_id = :userId AND f.id = :from AND f.user_id = :userId""")
                .param("userId", userId).param("from", from).param("into", into).update();
        jdbc.sql("DELETE FROM closet.garments WHERE user_id = :userId AND id = :from")
                .param("userId", userId).param("from", from).update();
    }

    /** 옷을 지운다. 입은 날(wears)도 FK ON DELETE CASCADE로 함께 지워진다. */
    int delete(UUID userId, Collection<String> ids) {
        return jdbc.sql("DELETE FROM closet.garments WHERE user_id = :userId AND id IN (:ids)")
                .param("userId", userId).param("ids", ids).update();
    }
}
