package com.fashionagent.closet;

import java.util.List;
import java.util.Set;

/**
 * 같은 옷인지 볼 때 이름 비교. 실제 e5로 재 보니 임베딩만으로는 "같은 니트를 스웨터라고 적은 것"(0.937)과
 * "같은 색 가디건"(0.978)을 가르지 못해서, 이름이 같은 종류(동의어 묶음)일 때만 합친다.
 */
final class GarmentNames {

    private GarmentNames() {
    }

    /** 같은 묶음이면 같은 옷 이름으로 본다. 마지막 낱말(머리말)로 찾는다. */
    private static final List<Set<String>> GROUPS = List.of(
            Set.of("니트", "스웨터", "니트스웨터"),
            Set.of("티셔츠", "티", "반팔티", "긴팔티"),
            Set.of("맨투맨", "스웨트셔츠"),
            Set.of("후드티", "후디", "후드", "후드티셔츠"),
            Set.of("셔츠", "남방", "와이셔츠"),
            Set.of("블라우스"),
            Set.of("청바지", "데님", "데님바지", "진"),
            Set.of("슬랙스", "정장바지"),
            Set.of("자켓", "재킷", "블레이저"),
            Set.of("패딩", "패딩점퍼", "다운점퍼"),
            Set.of("운동화", "스니커즈"),
            Set.of("구두", "로퍼"));

    private static final Set<String> SLEEVES = Set.of("반팔", "긴팔", "민소매");

    static boolean sameKind(String a, String b) {
        String na = norm(a);
        String nb = norm(b);
        if (na.equals(nb)) {
            return true;
        }
        if (!sleeve(a).equals(sleeve(b)) && !sleeve(a).isEmpty() && !sleeve(b).isEmpty()) {
            return false;
        }
        String ha = head(a);
        String hb = head(b);
        if (ha.equals(hb)) {
            return true;
        }
        return GROUPS.stream().anyMatch(g -> (g.contains(ha) || g.contains(na)) && (g.contains(hb) || g.contains(nb)));
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String head(String s) {
        String[] words = (s == null ? "" : s.trim()).split("\\s+");
        String h = words[words.length - 1];
        for (String sl : SLEEVES) {
            if (h.startsWith(sl) && h.length() > sl.length()) {
                h = h.substring(sl.length());
            }
        }
        return h;
    }

    private static String sleeve(String s) {
        if (s == null) {
            return "";
        }
        for (String sl : SLEEVES) {
            if (s.contains(sl)) {
                return sl;
            }
        }
        return "";
    }

}
