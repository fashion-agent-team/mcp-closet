package com.fashionagent.closet;

import java.util.List;
import java.util.Set;

/**
 * 같은 옷 후보(종류·색 계열·이름 묶음이 같고 유사도 0.90 이상)인데 clothes_inspect가 본 겉모습이 분명히 다르면 다른 옷으로 본다.
 * e5 임베딩은 "같은 색 니트"를 0.9 이상으로 붙여서, 톤(밝음↔어두움)과 무늬로 한 번 더 거른다.
 * 모르는 값(null)은 다르다고 보지 않는다. 사진마다 무늬를 빠뜨릴 수 있어서다.
 */
final class SameGarment {

    private SameGarment() {
    }

    private static final Set<String> PLAIN = Set.of("무지", "없음", "민무늬", "단색", "solid", "plain", "none");

    /** 같은 무늬를 다르게 부르는 말. 앞의 것으로 맞춘다. */
    private static final List<List<String>> SYNONYMS = List.of(
            List.of("줄무늬", "스트라이프", "stripe", "줄"),
            List.of("체크", "격자", "타탄", "깅엄", "check", "plaid"),
            List.of("도트", "물방울", "땡땡이", "dot"),
            List.of("꽃무늬", "플로럴", "floral"),
            List.of("호피", "레오파드", "leopard"));

    static boolean conflicts(String toneA, String patternA, String toneB, String patternB) {
        return toneConflicts(toneA, toneB) || patternConflicts(patternA, patternB);
    }

    /** light ↔ dark만 다르다고 본다. mid는 조명에 따라 양쪽으로 갈 수 있다. */
    static boolean toneConflicts(String a, String b) {
        return a != null && b != null && !a.equals(b) && !"mid".equals(a) && !"mid".equals(b);
    }

    static boolean patternConflicts(String a, String b) {
        String na = pattern(a);
        String nb = pattern(b);
        if (na == null || nb == null) {
            return false;
        }
        if (na.isEmpty() || nb.isEmpty()) {
            return !na.equals(nb);
        }
        return !na.contains(nb) && !nb.contains(na);
    }

    /** null = 모름, "" = 무지, 그 밖은 띄어쓰기 없앤 무늬 이름. */
    private static String pattern(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        String s = p.replaceAll("\\s+", "").toLowerCase();
        if (PLAIN.contains(s)) {
            return "";
        }
        for (List<String> group : SYNONYMS) {
            for (String word : group) {
                s = s.replace(word, group.get(0));
            }
        }
        return s;
    }
}
