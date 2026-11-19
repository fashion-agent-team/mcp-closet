package com.fashionagent.closet;

/** 임베딩할 문장에 쓰는 한국어 이름. 옷 설명과 상황 문장이 같은 말로 만나야 검색이 잘 된다. */
final class Labels {

    private Labels() {
    }

    static String category(String c) {
        return switch (c) {
            case "top" -> "상의";
            case "bottom" -> "하의";
            case "outer" -> "겉옷";
            case "dress" -> "원피스";
            case "shoes" -> "신발";
            default -> "기타";
        };
    }

    static String tone(String t) {
        return switch (t) {
            case "light" -> "밝은 톤";
            case "dark" -> "어두운 톤";
            default -> "중간 톤";
        };
    }

    static String formality(String f) {
        return switch (f) {
            case "formal" -> "정장, 격식 있는 자리";
            case "smart" -> "단정한 옷, 출근·모임";
            case "casual" -> "편한 옷, 일상";
            case "sporty" -> "운동복, 활동적인 날";
            default -> "격식 모름";
        };
    }

    /** e5는 저장할 글에 "passage: ", 찾는 글에 "query: "를 붙여야 제 성능이 난다(모델 카드). */
    static String passage(Garment.Normalized g) {
        StringBuilder sb = new StringBuilder("passage: ")
                .append(category(g.category())).append(' ').append(g.name()).append(". ")
                .append("색: ").append(g.colorName()).append(" (").append(tone(g.tone())).append("). ")
                .append("격식: ").append(formality(g.formality())).append('.');
        if (g.pattern() != null) {
            sb.append(" 무늬: ").append(g.pattern()).append('.');
        }
        if (g.material() != null) {
            sb.append(" 소재: ").append(g.material()).append('.');
        }
        if (g.notes() != null) {
            sb.append(' ').append(g.notes());
        }
        return sb.toString();
    }
}
