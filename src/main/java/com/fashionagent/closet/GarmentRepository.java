package com.fashionagent.closet;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** closet.garments, closet.wears. SQL은 앱 레포 specs/005-database/data-model.md 3절과 같다. */
@Repository
class GarmentRepository {

    private final JdbcClient jdbc;

    GarmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Match(String id, String name, double similarity) {
    }

    /** 같은 옷 후보: 내 옷 중 종류·색 계열이 같은 것을 가까운 순으로 몇 개. */
    List<Match> nearestSame(UUID userId, String category, String colorFamily, float[] q, int limit) {
        return jdbc.sql("""
                        SELECT id, name, 1 - (embedding <=> CAST(:q AS public.vector)) AS similarity FROM closet.garments
                         WHERE user_id = :userId AND category = :category AND color_family = :colorFamily AND embedding IS NOT NULL
                         ORDER BY embedding <=> CAST(:q AS public.vector) LIMIT :limit""")
                .param("q", Vectors.literal(q)).param("userId", userId)
                .param("category", category).param("colorFamily", colorFamily).param("limit", limit)
                .query((rs, i) -> new Match(rs.getString("id"), rs.getString("name"), rs.getDouble("similarity")))
                .list();
    }

    int countByUser(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM closet.garments WHERE user_id = :userId").param("userId", userId)
                .query(Integer.class).single();
    }

    void insert(String id, UUID userId, Garment.Normalized g, String descriptionJson, float[] embedding, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO closet.garments (id, user_id, category, name, color_name, color_family, tone, formality,
                                              description, embedding, first_seen_at, last_seen_at, created_at, updated_at)
                        VALUES (:id, :userId, :category, :name, :colorName, :colorFamily, :tone, :formality,
                                CAST(:description AS jsonb), CAST(:embedding AS public.vector), :now, :now, :now, :now)""")
                .param("id", id).param("userId", userId)
                .param("category", g.category()).param("name", g.name()).param("colorName", g.colorName())
                .param("colorFamily", g.colorFamily()).param("tone", g.tone()).param("formality", g.formality())
                .param("description", descriptionJson)
                .param("embedding", embedding == null ? null : Vectors.literal(embedding))
                .param("now", now)
                .update();
    }

    /** 같은 옷을 다시 봤을 때: 마지막으로 본 설명으로 바꾼다(종류·색 계열은 같으므로 그대로). */
    void update(String id, UUID userId, Garment.Normalized g, String descriptionJson, float[] embedding, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE closet.garments SET name = :name, color_name = :colorName, tone = :tone,
                               formality = CASE WHEN :formality = 'unknown' THEN formality ELSE :formality END,
                               description = CAST(:description AS jsonb), embedding = CAST(:embedding AS public.vector),
                               last_seen_at = GREATEST(last_seen_at, :now), updated_at = :now
                         WHERE id = :id AND user_id = :userId""")
                .param("id", id).param("userId", userId)
                .param("name", g.name()).param("colorName", g.colorName()).param("tone", g.tone())
                .param("formality", g.formality()).param("description", descriptionJson)
                .param("embedding", Vectors.literal(embedding)).param("now", now)
                .update();
    }

    /** 이 사람 옷 중 있는 id만. */
    List<String> ownedIds(UUID userId, Collection<String> ids) {
        return jdbc.sql("SELECT id FROM closet.garments WHERE user_id = :userId AND id IN (:ids)")
                .param("userId", userId).param("ids", ids).query(String.class).list();
    }

    void recordWear(UUID userId, String garmentId, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO closet.wears (user_id, garment_id, worn_on) VALUES (:userId, :garmentId, :day)
                        ON CONFLICT ON CONSTRAINT wears_garment_day_uq DO NOTHING""")
                .param("userId", userId).param("garmentId", garmentId).param("day", day)
                .update();
    }

    record Candidate(String id, String category, String name, String colorName, String colorFamily, String tone,
                     String formality, String material, LocalDate lastWorn, double similarity) {
    }

    /** 내 옷 전부를 질문과 가까운 순으로(한 사람 100벌 안팎이라 정확한 거리로 다 훑는다, research R3). */
    List<Candidate> rankedForUser(UUID userId, float[] q, Collection<String> excludeIds) {
        String exclude = excludeIds.isEmpty() ? "" : " AND g.id NOT IN (:exclude)";
        var spec = jdbc.sql("""
                        SELECT g.id, g.category, g.name, g.color_name, g.color_family, g.tone, g.formality, g.description ->> 'material' AS material,
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
}
