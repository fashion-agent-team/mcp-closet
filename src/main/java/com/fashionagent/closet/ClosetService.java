package com.fashionagent.closet;

import com.fashionagent.closet.embedding.E5;
import com.fashionagent.closet.embedding.ImageTextModel;
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
    private final ImageMatcher images;
    private final ImageLabels labels;
    private final ImageTextModel imageText;
    private final EmbeddingModel embedding;
    private final StyleKnowledge style;
    private final ClosetProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public ClosetService(GarmentRepository garments, ImageMatcher images, ImageLabels labels, ImageTextModel imageText,
                         EmbeddingModel embedding, StyleKnowledge style, ClosetProperties props, JsonMapper json, Clock clock) {
        this.garments = garments;
        this.images = images;
        this.labels = labels;
        this.imageText = imageText;
        this.embedding = embedding;
        this.style = style;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Saved(String inputId, String garmentId, boolean isNew, MaybeSame maybeSameAs) {
        Saved(String inputId, String garmentId, boolean isNew) {
            this(inputId, garmentId, isNew, null);
        }
    }

    /**
     * 사진 유사도가 기준 바로 아래(애매함)라 새 옷으로 저장했지만 같은 옷일 수도 있는 기존 옷.
     * agent가 GPT로 이번 사진과 이 설명을 비교해 같은 옷이면 closet_merge(garmentId=새 옷, intoGarmentId=이 옷)를 부른다.
     */
    public record MaybeSame(String garmentId, String name, String colorName, String pattern, String material,
                            String thumbnail, double similarity) {
    }

    /** closet_save. 같은 옷이면 새로 만들지 않고 합친다. */
    @Transactional
    public Map<String, Object> save(UUID userId, List<Garment> input) {
        return save(userId, input, List.of(), null);
    }

    /**
     * 같은 옷 판단:
     * <ul>
     * <li>옷 사진 벡터(garments[].embedding)와 embeddingModel이 있으면: 같은 종류이고 같은 모델 벡터가 있는 내 옷 중
     *     가장 가까운 옷이 기준({@link ImageMatcher#threshold()}) 이상이면 합친다. 색·이름·톤 규칙은 쓰지 않는다
     *     (DeepFashion 측정에서 색 판별이 사진마다 달라 같은 옷을 갈랐고, 톤 규칙은 맞게 합침을 줄이기만 했다).</li>
     * <li>벡터가 없으면: 예전 규칙(종류·색 계열·이름 묶음이 같고 글 유사도 0.90 이상, 톤·무늬 충돌 없음).</li>
     * </ul>
     * forceNewIds: 사용자가 "다른 옷이에요"라고 한 입력 id(Garment.id). 비슷해도 합치지 않고 새로 만든다.
     * 한 번에 받은 옷끼리는 서로 합치지 않고, 같은 기존 옷에 두 벌을 합치지도 않는다(한 사진에 같이 있으면 다른 옷).
     */
    @Transactional
    public Map<String, Object> save(UUID userId, List<Garment> input, List<String> forceNewIds) {
        return save(userId, input, forceNewIds, null);
    }

    @Transactional
    public Map<String, Object> save(UUID userId, List<Garment> input, List<String> forceNewIds, String embeddingModel) {
        if (input == null || input.isEmpty()) {
            throw ClosetException.badRequest("garments가 비어 있어요.");
        }
        if (input.size() > props.maxGarmentsPerSave()) {
            throw ClosetException.badRequest("한 번에 " + props.maxGarmentsPerSave() + "벌까지 저장할 수 있어요.");
        }
        String model = embeddingModel == null || embeddingModel.isBlank() ? null : embeddingModel.trim();
        if (model != null && model.length() > 40) {
            throw ClosetException.badRequest("embeddingModel은 40자 이내여야 해요.");
        }
        var normalized = new ArrayList<Garment.Normalized>();
        var imgs = new ArrayList<float[]>();
        var guesses = new ArrayList<ImageLabels.Guess>();
        for (Garment g : input) {
            if (g == null) {
                throw ClosetException.badRequest("garments에 빈 값이 있어요.");
            }
            Garment.Normalized n = g.normalize();
            float[] img = model == null ? null : images.accept(g.embedding());
            ImageLabels.Guess guess = img != null && model.equals(labels.model()) ? labels.guess(img, n.category(), n.name()) : null;
            normalized.add(guess == null ? n : n.withImageGuess(guess));
            imgs.add(img);
            guesses.add(guess);
        }
        List<float[]> vectors = embedAll(normalized.stream().map(Labels::passage).toList());

        OffsetDateTime now = OffsetDateTime.now(clock);
        int size = garments.countByUser(userId);
        var forceNew = forceNewIds == null ? java.util.Set.<String>of()
                : forceNewIds.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).collect(java.util.stream.Collectors.toSet());
        var taken = new java.util.HashSet<String>();
        var imageRows = new java.util.HashMap<String, List<GarmentRepository.ImageRow>>();
        var saved = new ArrayList<Saved>();
        for (int i = 0; i < input.size(); i++) {
            Garment g = input.get(i);
            Garment.Normalized n = normalized.get(i);
            float[] v = vectors == null ? null : vectors.get(i);
            float[] img = imgs.get(i);
            ImageLabels.Guess guess = guesses.get(i);
            boolean force = g.id() != null && forceNew.contains(g.id().trim());

            String matchId = null;
            String maybeId = null;
            double maybeSim = 0;
            String keptPattern = null;
            GarmentRepository.ImageRow matchRow = null;
            if (img != null) {
                if (!force) {
                    var rows = imageRows.computeIfAbsent(n.category(), c -> garments.imageRows(userId, c, model));
                    float[] q = images.key(img);
                    double best = -2;
                    for (var row : rows) {
                        if (taken.contains(row.id())) {
                            continue;
                        }
                        double sim = Vectors.dot(q, images.key(row.embedding()));
                        if (sim > best) {
                            best = sim;
                            matchRow = row;
                        }
                    }
                    if (matchRow != null && best >= images.threshold()) {
                        matchId = matchRow.id();
                    } else {
                        if (matchRow != null && best >= images.uncertainBelow()) {
                            maybeId = matchRow.id();
                            maybeSim = best;
                        }
                        matchRow = null;
                    }
                }
            } else if (v != null && !force) {
                var near = garments.nearestSame(userId, n.category(), n.colorFamily(), v, 5).stream()
                        .filter(m -> !taken.contains(m.id())).toList();
                var same = near.stream()
                        .filter(m -> m.similarity() >= props.sameGarmentThreshold() && GarmentNames.sameKind(m.name(), n.name()))
                        .filter(m -> !SameGarment.conflicts(n.tone(), n.pattern(), m.tone(), m.pattern()))
                        .findFirst();
                if (same.isPresent()) {
                    matchId = same.get().id();
                    keptPattern = same.get().pattern();
                } else if (thumbnail(g.thumbnail()) != null && !near.isEmpty()) {
                    // 사진 벡터가 없으면(인식 실패) 글로 가장 가까운 같은 종류·색 계열 옷을 알려 GPT가 사진으로 확인하게 한다
                    maybeId = near.get(0).id();
                    maybeSim = near.get(0).similarity();
                }
            }

            if (matchId != null) {
                // 이번 사진에서 무늬를 못 봤으면 전에 본 무늬를 남긴다(다음에 다른 무늬 옷과 가를 수 있게)
                Garment kept = (g.pattern() == null || g.pattern().isBlank()) && keptPattern != null ? g.withPattern(keptPattern) : g;
                garments.update(matchId, userId, n, describe(kept, matchId, guess), v, now);
                garments.setThumbnail(matchId, userId, thumbnail(g.thumbnail()), now);
                if (img != null) {
                    int max = props.image().maxCount();
                    garments.setImage(matchId, userId, model, ImageMatcher.mean(matchRow.embedding(), matchRow.count(), img, max),
                            Math.min(matchRow.count() + 1, max));
                }
                taken.add(matchId);
                saved.add(new Saved(g.id(), matchId, false));
                continue;
            }
            if (size >= props.maxClosetSize()) {
                throw ClosetException.badRequest("옷장이 가득 찼어요(" + props.maxClosetSize() + "벌).");
            }
            String id = Ids.garmentId();
            garments.insert(id, userId, n, describe(g, id, guess), v, now);
            garments.setThumbnail(id, userId, thumbnail(g.thumbnail()), now);
            if (img != null) {
                garments.setImage(id, userId, model, img, 1);
            }
            taken.add(id);
            size++;
            final double sim = Math.round(maybeSim * 1000) / 1000.0;
            saved.add(new Saved(g.id(), id, true, maybeId == null ? null : garments.brief(userId, maybeId)
                    .map(x -> new MaybeSame(x.id(), x.name(), x.colorName(), x.pattern(), x.material(), x.thumbnail(), sim))
                    .orElse(null)));
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

    /** 옷 작은 사진(base64 JPEG). 모양이 틀리거나 크면 저장하지 않는다(오류 아님, 사진 없이 저장). */
    static byte[] thumbnail(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        String s = base64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        byte[] b;
        try {
            b = java.util.Base64.getMimeDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
        boolean jpeg = b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
        return jpeg && b.length <= 49_152 ? b : null;
    }

    /** description(jsonb)에는 받은 Garment 전체를 두되 id는 옷장 id로 바꾸고, 사진 벡터(숫자 768개)·작은 사진은 따로 두므로 뺀다. */
    private String describe(Garment g, String garmentId, ImageLabels.Guess guess) {
        Garment copy = new Garment(garmentId, g.category(), g.name(), g.colorName(), g.colorFamily(), g.tone(), g.pattern(),
                g.material(), g.notes(), g.formality(), g.defects(), g.box(), g.position(), g.confidence(), null, null,
                g.subColorName(), g.subColorFamily(), g.patternCheckedBy(), g.patternGuess());
        if (guess == null) {
            return json.writeValueAsString(copy);
        }
        // clothes_inspect가 준 값은 그대로 두고, 사진에서 고른 값은 imageGuess에 따로 남긴다
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(copy);
        var ig = node.putObject("imageGuess");
        ig.put("name", guess.name());
        ig.put("pattern", guess.pattern());
        ig.put("formality", guess.formality());
        return json.writeValueAsString(node);
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
        return search(userId, situation, occasion, apparentTemperature, excludeGarmentIds, limit, null);
    }

    /**
     * imageQuery(영어, 예: "warm winter clothes")가 있고 글 모델이 켜져 있으면 옷 사진 벡터와도 비교해
     * e5 점수에 weight × 사진↔글 유사도를 더한다. 사진 벡터가 없는 옷은 있는 옷들의 평균을 더해 손해 보지 않게 한다.
     */
    public Map<String, Object> search(UUID userId, String situation, String occasion, Double apparentTemperature,
                                      List<String> excludeGarmentIds, Integer limit, String imageQuery) {
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
        Map<String, Double> imageSim = imageSimilarities(userId, imageQuery);
        double w = props.image().text().weight();
        double fill = imageSim.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var ranked = garments.rankedForUser(userId, q, exclude).stream()
                .filter(g -> !CandidateFilter.excluded(g, occ, apparentTemperature))
                .map(g -> g.withSimilarity(g.similarity() + (imageSim.isEmpty() ? 0 : w * imageSim.getOrDefault(g.id(), fill))
                        + CandidateFilter.formalityBonus(occ, g.formality())
                        - CandidateFilter.recencyPenalty(g.lastWorn(), today)))
                .sorted(java.util.Comparator.comparingDouble(GarmentRepository.Candidate::similarity).reversed())
                .toList();
        return result(diversify(ranked, n), notes, false);
    }

    private Map<String, Double> imageSimilarities(UUID userId, String imageQuery) {
        if (imageQuery == null || imageQuery.isBlank() || !imageText.enabled()) {
            return Map.of();
        }
        if (imageQuery.length() > 200) {
            throw ClosetException.badRequest("imageQuery는 200자 이내여야 해요.");
        }
        try {
            return garments.imageSimilarities(userId, imageText.embed("a photo of " + imageQuery.trim()), imageText.model());
        } catch (RuntimeException e) {
            // 사진↔글은 덤이라 실패해도 글 검색 결과는 돌려준다
            log.warn("imageQuery 임베딩 실패: {}", e.toString());
            return Map.of();
        }
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

    /** closet_list. "내 옷장에 뭐 있어?", "바지 뭐 있지?". */
    public Map<String, Object> list(UUID userId, String category, Integer limit) {
        String cat = category == null || category.isBlank() ? null : category.trim().toLowerCase();
        if (cat != null && !Garment.CATEGORIES.contains(cat)) {
            throw ClosetException.badRequest("category는 top·bottom·outer·dress·shoes·other 중 하나여야 해요.");
        }
        int n = limit == null || limit <= 0 ? 50 : Math.min(limit, props.maxClosetSize());
        var m = new LinkedHashMap<String, Object>();
        m.put("garments", garments.listForUser(userId, cat, n));
        m.put("total", garments.countByUser(userId, cat));
        return m;
    }

    /**
     * closet_remove. 버린 옷, 잘못 저장한 옷을 지운다. 남의 옷이나 없는 id가 섞이면 아무것도 지우지 않는다.
     * 그 옷의 입은 날 기록도 함께 지워져 closet_outfits에서 빠진다.
     */
    @Transactional
    public Map<String, Object> remove(UUID userId, List<String> garmentIds) {
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
            throw ClosetException.badRequest("한 번에 " + props.maxGarmentsPerSave() + "벌까지 지울 수 있어요.");
        }
        var owned = garments.ownedIds(userId, ids);
        if (owned.size() != ids.size()) {
            var missing = new ArrayList<>(ids);
            missing.removeAll(owned);
            throw ClosetException.notFound("옷장에 없는 옷이에요: " + String.join(", ", missing));
        }
        return Map.of("removed", garments.delete(userId, ids));
    }

    /**
     * closet_merge. 따로 저장된 두 옷이 같은 옷일 때 garmentId를 intoGarmentId에 합친다: 입은 날을 옮기고, 사진 벡터는 사진 수로
     * 가중 평균하고, 설명은 더 최근에 본 쪽을 남긴 뒤 garmentId를 지운다. 종류가 다르면 합치지 않는다.
     */
    @Transactional
    public Map<String, Object> merge(UUID userId, String garmentId, String intoGarmentId) {
        if (garmentId == null || garmentId.isBlank() || intoGarmentId == null || intoGarmentId.isBlank()) {
            throw ClosetException.badRequest("garmentId와 intoGarmentId가 필요해요.");
        }
        String from = garmentId.trim();
        String into = intoGarmentId.trim();
        if (from.equals(into)) {
            throw ClosetException.badRequest("같은 옷끼리는 합칠 수 없어요.");
        }
        var a = garments.mergeRow(userId, from);
        var b = garments.mergeRow(userId, into);
        if (a.isEmpty() || b.isEmpty()) {
            throw ClosetException.notFound("옷장에 없는 옷이에요: " + (a.isEmpty() ? from : into));
        }
        if (!a.get().category().equals(b.get().category())) {
            throw ClosetException.badRequest("종류가 다른 옷은 합칠 수 없어요.");
        }
        var f = a.get();
        var t = b.get();
        if (f.image() != null && t.image() != null && f.model().equals(t.model())) {
            int max = props.image().maxCount();
            garments.setImage(into, userId, t.model(), ImageMatcher.combine(t.image(), t.count(), f.image(), f.count(), max),
                    Math.min(t.count() + f.count(), max));
        } else if (f.image() != null && t.image() == null) {
            garments.setImage(into, userId, f.model(), f.image(), f.count());
        }
        garments.mergeInto(userId, from, into);
        return Map.of("merged", from, "into", into);
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
