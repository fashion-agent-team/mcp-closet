package com.fashionagent.closet;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옷 사진 벡터로 옷 이름·무늬·격식을 고른다(zero-shot). clothes_inspect의 이름은 "티셔츠"·"바지"처럼 굵고 무늬는 늘 비어 있어서,
 * FashionSigLIP 글 벡터 표(resources/image-labels/fsiglip-labels.json, tools/image-labels/make_labels.py로 만듦)와
 * 사진 벡터의 유사도로 "후드티", "청바지", "줄무늬"를 고른다. 글 모델은 서버에 없어도 된다.
 *
 * <p>측정(DeepFashion 옷 600벌, 옷장 60벌에서 "청바지"·"꽃무늬 옷" 같은 질문 15개의 위 5개 정답률): 옷 설명 글만 41% → 이 이름·무늬를 넣은 글 69%.
 */
@Component
public class ImageLabels {

    private static final Logger log = LoggerFactory.getLogger(ImageLabels.class);
    static final String RESOURCE = "image-labels/fsiglip-labels.json";

    record Label(String ko, List<String> categories, String formality, float[] vector) {
    }

    /** 고른 값. pattern은 무지로 보이면 null. */
    public record Guess(String name, String pattern, String formality) {
    }

    /** clothes_inspect 이름 → 바꿔도 되는 더 자세한 이름(표의 ko). 표에 없는 이름(옷·겉옷·상의 등)은 제한 없음. */
    static final Map<String, java.util.Set<String>> FAMILY = Map.ofEntries(
            Map.entry("티셔츠", java.util.Set.of("티셔츠", "프린트 티셔츠", "민소매", "폴로 셔츠", "맨투맨")),
            Map.entry("셔츠", java.util.Set.of("셔츠", "블라우스", "폴로 셔츠")),
            Map.entry("니트", java.util.Set.of("니트", "맨투맨", "후드티")),
            Map.entry("가디건", java.util.Set.of("카디건")),
            Map.entry("카디건", java.util.Set.of("카디건")),
            Map.entry("자켓", java.util.Set.of("자켓", "정장 자켓")),
            Map.entry("재킷", java.util.Set.of("자켓", "정장 자켓")),
            Map.entry("코트", java.util.Set.of("코트")),
            Map.entry("조끼", java.util.Set.of("조끼")),
            Map.entry("바지", java.util.Set.of("바지", "청바지", "슬랙스", "레깅스")),
            Map.entry("반바지", java.util.Set.of("반바지")),
            Map.entry("치마", java.util.Set.of("치마")),
            Map.entry("원피스", java.util.Set.of("원피스", "점프수트")));

    private final String model;
    private final List<Label> names = new ArrayList<>();
    private final List<Label> patterns = new ArrayList<>();
    private final double patternMargin;

    public ImageLabels(JsonMapper json) {
        try (InputStream in = ImageLabels.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("없음: " + RESOURCE);
            }
            JsonNode root = json.readTree(in);
            this.model = root.get("model").asString();
            this.patternMargin = root.get("patternMargin").asDouble();
            for (JsonNode n : root.get("names")) {
                var cats = new ArrayList<String>();
                n.get("categories").forEach(c -> cats.add(c.asString()));
                names.add(new Label(n.get("ko").asString(), cats, n.get("formality").asString(), vector(n.get("vector"))));
            }
            for (JsonNode p : root.get("patterns")) {
                JsonNode ko = p.get("ko");
                patterns.add(new Label(ko == null || ko.isNull() ? null : ko.asString(), List.of(), null, vector(p.get("vector"))));
            }
        } catch (IOException e) {
            throw new IllegalStateException("옷 이름 표를 읽지 못했어요: " + RESOURCE, e);
        }
        log.info("옷 사진 이름 표: 이름 {}개, 무늬 {}개 ({})", names.size(), patterns.size(), model);
    }

    /** 이 표를 만든 사진 모델. clothes_inspect의 embeddingModel과 같을 때만 쓴다. */
    public String model() {
        return model;
    }

    /**
     * @param v        길이 1인 사진 벡터(보정 행렬을 쓰기 전 원래 벡터)
     * @param category clothes_inspect가 정한 종류. 그 종류에 맞는 이름 중에서만 고른다(바지를 셔츠라고 하지 않게)
     */
    public Guess guess(float[] v, String category) {
        return guess(v, category, null);
    }

    /**
     * clothes_inspect가 이미 구체적인 이름(자켓·바지 등)을 줬으면 그 이름을 더 자세히 하는 쪽(바지 → 청바지·슬랙스)으로만 고른다.
     * 2026-11-12 시뮬에서 자켓을 조끼로, 패딩을 카디건으로 바꿔 틀렸음. "옷"·"겉옷"처럼 넓은 이름이면 종류 안에서 다 고른다.
     */
    public Guess guess(float[] v, String category, String inspectName) {
        return guess(v, category, inspectName, null);
    }

    /** 청바지라고 불러도 되는 색 계열. 2026-11-13 실서버 시뮬: 베이지 면바지를 "베이지 청바지"로 저장함. */
    static final java.util.Set<String> DENIM_COLORS = java.util.Set.of("blue", "navy", "black", "gray", "unknown");

    /** colorFamily가 청바지 색이 아니면(베이지·카키·흰색 등) 청바지는 고르지 않는다. null이면 제한 없음. */
    public Guess guess(float[] v, String category, String inspectName, String colorFamily) {
        boolean noDenim = colorFamily != null && !DENIM_COLORS.contains(colorFamily);
        java.util.Set<String> family = inspectName == null ? null : FAMILY.get(inspectName.strip());
        Label best = null;
        double bs = -2;
        for (Label l : names) {
            if (!l.categories().contains(category)) {
                continue;
            }
            if (family != null && !family.contains(l.ko())) {
                continue;
            }
            if (noDenim && "청바지".equals(l.ko())) {
                continue;
            }
            double s = Vectors.dot(v, l.vector());
            if (s > bs) {
                bs = s;
                best = l;
            }
        }
        String pattern = null;
        double solid = Vectors.dot(v, patterns.get(0).vector());
        double ps = -2;
        for (Label p : patterns.subList(1, patterns.size())) {
            double s = Vectors.dot(v, p.vector());
            if (s > ps) {
                ps = s;
                pattern = p.ko();
            }
        }
        if (ps - solid <= patternMargin) {
            pattern = null;
        }
        return best == null ? new Guess(null, pattern, null)
                : new Guess(best.ko(), pattern, "unknown".equals(best.formality()) ? null : best.formality());
    }

    private static float[] vector(JsonNode arr) {
        float[] v = new float[arr.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return v;
    }

    Map<String, Integer> sizes() {
        return Map.of("names", names.size(), "patterns", patterns.size());
    }
}
