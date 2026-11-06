package com.fashionagent.closet;

import java.util.Map;
import java.util.Set;

/**
 * closet_search의 occasion·apparentTemperature 필터. 앱의 lib/styling/schedule_rules.dart, weather_rules.dart에서
 * "이 자리·날씨에 맞지 않아요"라고 말하는 옷만 뺀다. 애매한 것(격식 unknown 등)은 남기고 GPT가 고르게 한다.
 */
final class CandidateFilter {

    private CandidateFilter() {
    }

    static final Set<String> OCCASIONS = Set.of("mourning", "wedding", "formal", "business", "active", "casual");

    private static final Map<String, Set<String>> EXCLUDED_FORMALITY = Map.of(
            "mourning", Set.of("casual", "sporty"),
            "wedding", Set.of("sporty"),
            "formal", Set.of("casual", "sporty"),
            "business", Set.of("sporty"),
            "active", Set.of("formal"),
            "casual", Set.of());

    private static final Set<String> BRIGHT = Set.of("red", "pink", "orange", "yellow", "green", "purple");

    /**
     * 자리에 잘 맞는 격식이면 점수를 조금 올린다. e5 점수는 옷끼리 0.77~0.84로 몰려 있어서
     * 이것만으로는 면접에 정장이 위로 오지 않을 때가 있다. 빼는 것({@link #excluded})과 달리 순서만 바꾼다.
     */
    static double formalityBonus(String occasion, String formality) {
        if (occasion == null) {
            return 0;
        }
        return switch (occasion) {
            case "formal", "mourning" -> switch (formality) { case "formal" -> 0.06; case "smart" -> 0.03; default -> 0; };
            case "wedding" -> switch (formality) { case "smart" -> 0.06; case "formal" -> 0.05; default -> 0; };
            case "business" -> switch (formality) { case "smart" -> 0.06; case "formal" -> 0.04; default -> 0; };
            case "active" -> switch (formality) { case "sporty" -> 0.06; case "casual" -> 0.03; default -> 0; };
            case "casual" -> switch (formality) { case "casual" -> 0.04; case "sporty", "smart" -> 0.02; default -> 0; };
            default -> 0;
        };
    }

    /**
     * 최근에 입은 옷은 점수를 조금 낮춘다(빼지는 않음: 옷이 적은 사람은 같은 옷을 또 입어야 한다).
     * 어제 0.06, 그제 0.04, 일주일 안 0.02. 오늘 입은 옷은 같은 날 다시 고르는 중일 수 있어 그대로 둔다.
     */
    static double recencyPenalty(java.time.LocalDate lastWorn, java.time.LocalDate today) {
        if (lastWorn == null) {
            return 0;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(lastWorn, today);
        if (days == 1) {
            return 0.06;
        }
        if (days == 2) {
            return 0.04;
        }
        return days >= 3 && days <= 7 ? 0.02 : 0;
    }

    /** 이 옷을 후보에서 빼야 하면 true. */
    static boolean excluded(GarmentRepository.Candidate g, String occasion, Double apparentTemperature) {
        if (occasion != null) {
            if (EXCLUDED_FORMALITY.getOrDefault(occasion, Set.of()).contains(g.formality())) {
                return true;
            }
            if (occasion.equals("mourning") && BRIGHT.contains(g.colorFamily())) {
                return true;
            }
            // 흰 원피스·겉옷은 신부 옷과 겹쳐 보일 수 있음. 청바지는 너무 편해 보임
            if (occasion.equals("wedding") && (("white".equals(g.colorFamily()) && Set.of("dress", "outer").contains(g.category()))
                    || g.name().contains("청바지"))) {
                return true;
            }
        }
        if (apparentTemperature != null) {
            String material = g.material() == null ? "" : g.material();
            boolean warm = material.contains("니트") || material.contains("패딩") || material.contains("울")
                    || g.name().contains("패딩") || g.name().contains("니트") || g.name().contains("스웨터") || g.name().contains("코트");
            boolean cool = material.contains("린넨") || g.name().contains("반팔") || g.name().contains("반바지")
                    || g.name().contains("민소매");
            double t = apparentTemperature;
            if (t >= 23 && (warm || g.category().equals("outer"))) {
                return true;
            }
            if (t < 15 && cool) {
                return true;
            }
        }
        return false;
    }
}
