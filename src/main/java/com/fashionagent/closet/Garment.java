package com.fashionagent.closet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Set;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * clothes_inspect가 만든 옷 한 벌(앱 레포 docs/contracts/mcp-tools.md "Garment"). 사진은 없고 설명만 있다.
 * 모르는 필드가 와도 버리지 않고 description(jsonb)에 그대로 남기려고, 저장할 때는 원래 JSON을 쓴다({@link ClosetService}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Garment(
        @ToolParam(required = false, description = "clothes_inspect가 붙인 id. 예: g1") String id,
        @ToolParam(description = "top | bottom | outer | dress | shoes | other") String category,
        @ToolParam(description = "옷 이름. 예: 니트") String name,
        @ToolParam(description = "색 이름. 예: 차콜 그레이") String colorName,
        @ToolParam(description = "black white gray navy beige brown khaki blue red pink orange yellow green purple unknown") String colorFamily,
        @ToolParam(description = "light | mid | dark") String tone,
        @ToolParam(required = false, description = "무늬. 없으면 null") String pattern,
        @ToolParam(required = false, description = "소재 추정. 예: 니트 같음") String material,
        @ToolParam(required = false, description = "기타 메모") String notes,
        @ToolParam(required = false, description = "formal | smart | casual | sporty | unknown") String formality,
        @ToolParam(required = false, description = "얼룩·찢어짐 등") List<Defect> defects,
        @ToolParam(required = false, description = "사진 속 위치(0~1)") Box box,
        @ToolParam(required = false, description = "left | center | right") String position,
        @ToolParam(required = false, description = "0~1") Double confidence,
        @ToolParam(required = false, description = "clothes_inspect가 준 옷 사진 벡터(768개). 그대로 넘기세요. 같은 옷 판단에 씀") List<Double> embedding) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Defect(String kind, String where, Double confidence,
                         @ToolParam(required = false, description = "model | gpt. clothes_inspect가 누가 확인했는지") String checkedBy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Box(Double x, Double y, Double w, Double h) {
    }

    Garment withPattern(String p) {
        return new Garment(id, category, name, colorName, colorFamily, tone, p, material, notes, formality, defects, box,
                position, confidence, embedding);
    }

    static final Set<String> CATEGORIES = Set.of("top", "bottom", "outer", "dress", "shoes", "other");
    static final Set<String> COLOR_FAMILIES = Set.of("black", "white", "gray", "navy", "beige", "brown", "khaki",
            "blue", "red", "pink", "orange", "yellow", "green", "purple", "unknown");
    static final Set<String> TONES = Set.of("light", "mid", "dark");
    static final Set<String> FORMALITIES = Set.of("formal", "smart", "casual", "sporty", "unknown");

    /** DB CHECK 제약에 맞춘 값. 종류가 없으면 저장할 수 없고, 나머지는 모르면 unknown·mid 등으로 둔다. */
    record Normalized(String category, String name, String colorName, String colorFamily, String tone, String formality,
                      String pattern, String material, String notes) {

        /**
         * 사진에서 고른 값으로 채운다: 이름은 사진 쪽(clothes_inspect 이름은 "티셔츠"처럼 굵어서), 무늬·격식은 비어 있을 때만.
         * 측정은 {@link ImageLabels}.
         */
        Normalized withImageGuess(ImageLabels.Guess g) {
            return new Normalized(category,
                    g.name() == null ? name : g.name(),
                    colorName, colorFamily, tone,
                    "unknown".equals(formality) && g.formality() != null ? g.formality() : formality,
                    pattern == null && g.pattern() != null ? g.pattern() : pattern,
                    material, notes);
        }
    }

    Normalized normalize() {
        String cat = lower(category);
        if (cat == null || !CATEGORIES.contains(cat)) {
            throw ClosetException.badRequest("category는 top·bottom·outer·dress·shoes·other 중 하나여야 해요.");
        }
        String fam = lower(colorFamily);
        String tn = lower(tone);
        String form = lower(formality);
        return new Normalized(cat,
                cut(blankTo(name, Labels.category(cat)), 50),
                cut(blankTo(colorName, "모르는 색"), 30),
                fam != null && COLOR_FAMILIES.contains(fam) ? fam : "unknown",
                tn != null && TONES.contains(tn) ? tn : "mid",
                form != null && FORMALITIES.contains(form) ? form : "unknown",
                trimToNull(pattern), trimToNull(material), trimToNull(notes));
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase();
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
