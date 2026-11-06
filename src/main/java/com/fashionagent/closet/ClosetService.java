package com.fashionagent.closet;

import com.fashionagent.closet.embedding.E5;
import com.fashionagent.closet.style.StyleKnowledge;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 도구 세 개의 실제 일. 입출력 모양은 앱 레포 docs/contracts/mcp-tools.md "mcp-closet". */
@Service
public class ClosetService {

    private static final Logger log = LoggerFactory.getLogger(ClosetService.class);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final GarmentRepository garments;
    private final EmbeddingModel embedding;
    private final StyleKnowledge style;
    private final ClosetProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public ClosetService(GarmentRepository garments, EmbeddingModel embedding, StyleKnowledge style,
                         ClosetProperties props, JsonMapper json, Clock clock) {
        this.garments = garments;
        this.embedding = embedding;
        this.style = style;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public record Saved(String inputId, String garmentId, boolean isNew) {
    }

    /** closet_save. 같은 옷(종류·색 계열·이름 묶음이 같고 유사도가 기준 이상)이면 새로 만들지 않고 합친다. */
    @Transactional
    public Map<String, Object> save(UUID userId, List<Garment> input) {
        if (input == null || input.isEmpty()) {
            throw ClosetException.badRequest("garments가 비어 있어요.");
        }
        if (input.size() > props.maxGarmentsPerSave()) {
            throw ClosetException.badRequest("한 번에 " + props.maxGarmentsPerSave() + "벌까지 저장할 수 있어요.");
        }
        var normalized = new ArrayList<Garment.Normalized>();
        for (Garment g : input) {
            if (g == null) {
                throw ClosetException.badRequest("garments에 빈 값이 있어요.");
            }
            normalized.add(g.normalize());
        }
        List<float[]> vectors = embedAll(normalized.stream().map(Labels::passage).toList());

        OffsetDateTime now = OffsetDateTime.now(clock);
        int size = garments.countByUser(userId);
        var saved = new ArrayList<Saved>();
        for (int i = 0; i < input.size(); i++) {
            Garment g = input.get(i);
            Garment.Normalized n = normalized.get(i);
            float[] v = vectors == null ? null : vectors.get(i);
            var same = v == null ? java.util.Optional.<GarmentRepository.Match>empty()
                    : garments.nearestSame(userId, n.category(), n.colorFamily(), v, 5).stream()
                            .filter(m -> m.similarity() >= props.sameGarmentThreshold() && GarmentNames.sameKind(m.name(), n.name()))
                            .findFirst();
            if (same.isPresent()) {
                String id = same.get().id();
                garments.update(id, userId, n, describe(g, id), v, now);
                saved.add(new Saved(g.id(), id, false));
                continue;
            }
            if (size >= props.maxClosetSize()) {
                throw ClosetException.badRequest("옷장이 가득 찼어요(" + props.maxClosetSize() + "벌).");
            }
            String id = Ids.garmentId();
            garments.insert(id, userId, n, describe(g, id), v, now);
            size++;
            saved.add(new Saved(g.id(), id, true));
        }
        return Map.of("saved", saved);
    }

    /** 모델이 잠시 안 되면 임베딩 없이 저장한다(스키마가 null을 허용, 같은 옷 합치기·검색에서는 빠짐). */
    private List<float[]> embedAll(List<String> texts) {
        try {
            return embedding.embed(texts);
        } catch (RuntimeException e) {
            log.warn("옷 임베딩 실패, 임베딩 없이 저장: {}", e.toString());
            return null;
        }
    }

    /** description(jsonb)에는 받은 Garment 전체를 두되 id는 옷장 id로 바꾼다. */
    private String describe(Garment g, String garmentId) {
        Garment copy = new Garment(garmentId, g.category(), g.name(), g.colorName(), g.colorFamily(), g.tone(), g.pattern(),
                g.material(), g.notes(), g.formality(), g.defects(), g.box(), g.position(), g.confidence());
        return json.writeValueAsString(copy);
    }

    /** closet_wear. 남의 옷이나 없는 옷 id가 섞이면 아무것도 기록하지 않는다. */
    @Transactional
    public Map<String, Object> wear(UUID userId, List<String> garmentIds, String date) {
        if (garmentIds == null || garmentIds.isEmpty()) {
            throw ClosetException.badRequest("garmentIds가 비어 있어요.");
        }
        var ids = new LinkedHashSet<String>();
        for (String id : garmentIds) {
            if (id == null || id.isBlank()) {
                throw ClosetException.badRequest("garmentIds에 빈 값이 있어요.");
            }
            ids.add(id.trim());
        }
        if (ids.size() > props.maxGarmentsPerSave()) {
            throw ClosetException.badRequest("한 번에 " + props.maxGarmentsPerSave() + "벌까지 기록할 수 있어요.");
        }
        LocalDate day = parseDate(date);
        var owned = garments.ownedIds(userId, ids);
        if (owned.size() != ids.size()) {
            var missing = new ArrayList<>(ids);
            missing.removeAll(owned);
            throw ClosetException.notFound("옷장에 없는 옷이에요: " + String.join(", ", missing));
        }
        ids.forEach(id -> garments.recordWear(userId, id, day));
        return Map.of("recorded", ids.size());
    }

    private LocalDate parseDate(String date) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        if (date == null || date.isBlank()) {
            return today;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date.trim().length() > 10 ? date.trim().substring(0, 10) : date.trim());
        } catch (RuntimeException e) {
            throw ClosetException.badRequest("date는 2026-11-04 같은 모양이어야 해요.");
        }
        if (day.isAfter(today.plusDays(1))) {
            throw ClosetException.badRequest("앞으로의 날짜에는 입은 날을 기록할 수 없어요.");
        }
        return day;
    }

    public record CandidateOut(String garmentId, String category, String name, String colorName, String formality,
                               LocalDate lastWorn, double score) {
    }

    /** closet_search. RAG의 검색 단계: 내 옷 후보 + 코디 지식 조각. 추천 문장은 agent의 GPT가 만든다. */
    public Map<String, Object> search(UUID userId, String situation, String occasion, Double apparentTemperature,
                                      List<String> excludeGarmentIds, Integer limit) {
        if (situation == null || situation.isBlank()) {
            throw ClosetException.badRequest("situation이 없어요.");
        }
        if (situation.length() > 500) {
            throw ClosetException.badRequest("situation은 500자 이내여야 해요.");
        }
        String occ = occasion == null || occasion.isBlank() ? null : occasion.trim().toLowerCase();
        if (occ != null && !CandidateFilter.OCCASIONS.contains(occ)) {
            throw ClosetException.badRequest("occasion은 mourning·wedding·formal·business·active·casual 중 하나여야 해요.");
        }
        int n = limit == null || limit <= 0 ? props.defaultLimit() : Math.min(limit, props.maxLimit());
        var exclude = excludeGarmentIds == null ? List.<String>of()
                : excludeGarmentIds.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();

        float[] q;
        try {
            q = embedding.embed(E5.query(situation));
        } catch (RuntimeException e) {
            log.warn("검색어 임베딩 실패: {}", e.toString());
            throw ClosetException.upstreamFailed("지금은 옷장을 찾지 못했어요.");
        }
        var notes = style.notesFor(q, occ, apparentTemperature, mentionsRain(situation), props.styleNotes());

        boolean empty = garments.countByUser(userId) == 0;
        if (empty) {
            return result(List.of(), notes, true);
        }
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        var ranked = garments.rankedForUser(userId, q, exclude).stream()
                .filter(g -> !CandidateFilter.excluded(g, occ, apparentTemperature))
                .map(g -> g.withSimilarity(g.similarity() + CandidateFilter.formalityBonus(occ, g.formality())
                        - CandidateFilter.recencyPenalty(g.lastWorn(), today)))
                .sorted(java.util.Comparator.comparingDouble(GarmentRepository.Candidate::similarity).reversed())
                .toList();
        return result(diversify(ranked, n), notes, false);
    }

    /** 비 문서는 상황 문장에 비 이야기가 있을 때만 넣는다(아니면 어느 검색에나 끼어서). */
    static boolean mentionsRain(String situation) {
        return situation.contains("비 ") || situation.endsWith("비") || situation.contains("비가") || situation.contains("비와")
                || situation.contains("우산") || situation.contains("장마") || situation.contains("소나기") || situation.contains("강수");
    }

    /**
     * 가까운 순서를 지키되 종류(상의·하의·겉옷·신발…)가 골고루 들어가게 종류별로 돌아가며 고른다.
     * 그냥 위에서 n개를 자르면 상의만 8벌이 나올 수 있어서다.
     */
    static List<CandidateOut> diversify(List<GarmentRepository.Candidate> ranked, int n) {
        var byCategory = new LinkedHashMap<String, List<GarmentRepository.Candidate>>();
        for (var g : ranked) {
            byCategory.computeIfAbsent(g.category(), k -> new ArrayList<>()).add(g);
        }
        var out = new ArrayList<CandidateOut>();
        for (int round = 0; out.size() < n; round++) {
            boolean any = false;
            for (var list : byCategory.values()) {
                if (round < list.size() && out.size() < n) {
                    var g = list.get(round);
                    out.add(new CandidateOut(g.id(), g.category(), g.name(), g.colorName(), g.formality(), g.lastWorn(),
                            Math.round(g.similarity() * 100) / 100.0));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return out;
    }

    public record OutfitGarment(String garmentId, String category, String name, String colorName, String formality) {
    }

    public record Outfit(LocalDate date, List<OutfitGarment> garments) {
    }

    /**
     * closet_outfits. 같은 날 입은 옷(closet_wear 기록)을 한 벌 조합으로 묶어 최근 날부터 돌려준다.
     * garmentId를 주면 그 옷을 입은 날만("이 셔츠 지난번에 뭐랑 입었지?").
     */
    public Map<String, Object> outfits(UUID userId, String from, String to, String garmentId, Integer limit) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        LocalDate end = to == null || to.isBlank() ? today : parseDay(to, "to");
        LocalDate start = from == null || from.isBlank() ? end.minusDays(90) : parseDay(from, "from");
        if (start.isAfter(end)) {
            throw ClosetException.badRequest("from이 to보다 뒤예요.");
        }
        int n = limit == null || limit <= 0 ? 5 : Math.min(limit, 30);
        String gid = garmentId == null || garmentId.isBlank() ? null : garmentId.trim();
        if (gid != null && garments.ownedIds(userId, List.of(gid)).isEmpty()) {
            throw ClosetException.notFound("옷장에 없는 옷이에요: " + gid);
        }
        var days = garments.wornDays(userId, start, end, gid, n);
        if (days.isEmpty()) {
            return Map.of("outfits", List.of());
        }
        var byDay = new LinkedHashMap<LocalDate, List<OutfitGarment>>();
        days.forEach(d -> byDay.put(d, new ArrayList<>()));
        for (var row : garments.wornOn(userId, days)) {
            byDay.get(row.day()).add(new OutfitGarment(row.id(), row.category(), row.name(), row.colorName(), row.formality()));
        }
        var out = new ArrayList<Outfit>();
        byDay.forEach((d, list) -> out.add(new Outfit(d, list)));
        return Map.of("outfits", out);
    }

    private static LocalDate parseDay(String value, String field) {
        try {
            return LocalDate.parse(value.trim().length() > 10 ? value.trim().substring(0, 10) : value.trim());
        } catch (RuntimeException e) {
            throw ClosetException.badRequest(field + "는 2026-11-04 같은 모양이어야 해요.");
        }
    }

    private static Map<String, Object> result(List<CandidateOut> candidates, List<StyleKnowledge.Note> notes, boolean empty) {
        var m = new LinkedHashMap<String, Object>();
        m.put("candidates", candidates);
        m.put("styleNotes", notes);
        m.put("empty", empty);
        return m;
    }
}
