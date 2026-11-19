package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CandidateFilterTest {

    static GarmentRepository.Candidate g(String id, String category, String name, String family, String formality, String material, double sim) {
        return new GarmentRepository.Candidate(id, category, name, name, family, "mid", formality, material, null, sim);
    }

    @Test
    @DisplayName("자리별로 앱 규칙(schedule_rules)이 안 맞다고 하는 옷만 뺀다")
    void occasion() {
        assertThat(CandidateFilter.excluded(g("a", "top", "후드티", "gray", "sporty", null, 0), "formal", null)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "셔츠", "white", "unknown", null, 0), "formal", null)).isFalse();
        assertThat(CandidateFilter.excluded(g("a", "top", "셔츠", "red", "formal", null, 0), "mourning", null)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "dress", "원피스", "white", "formal", null, 0), "wedding", null)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "셔츠", "white", "smart", null, 0), "wedding", null)).isFalse();
        assertThat(CandidateFilter.excluded(g("a", "bottom", "청바지", "blue", "casual", "데님", 0), "wedding", null)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "정장 셔츠", "white", "formal", null, 0), "active", null)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "후드티", "gray", "sporty", null, 0), "casual", null)).isFalse();
    }

    @Test
    @DisplayName("체감 23도 이상이면 겉옷·따뜻한 소재, 15도 미만이면 반팔·린넨을 뺀다")
    void temperature() {
        assertThat(CandidateFilter.excluded(g("a", "outer", "자켓", "gray", "smart", null, 0), null, 25.0)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "스웨터", "gray", "smart", "니트 같음", 0), null, 24.0)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "outer", "자켓", "gray", "smart", null, 0), null, 9.5)).isFalse();
        assertThat(CandidateFilter.excluded(g("a", "top", "반팔 티셔츠", "white", "casual", null, 0), null, 9.5)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "셔츠", "white", "smart", "린넨", 0), null, 14.0)).isTrue();
        assertThat(CandidateFilter.excluded(g("a", "top", "반팔 티셔츠", "white", "casual", null, 0), null, 20.0)).isFalse();
    }

    @Test
    @DisplayName("자리에 맞는 격식이면 점수를 조금 올리고, 비 이야기는 낱말로 알아본다")
    void bonusAndRain() {
        assertThat(CandidateFilter.formalityBonus("formal", "formal")).isGreaterThan(CandidateFilter.formalityBonus("formal", "smart"));
        assertThat(CandidateFilter.formalityBonus("active", "sporty")).isPositive();
        assertThat(CandidateFilter.formalityBonus("active", "formal")).isZero();
        assertThat(CandidateFilter.formalityBonus(null, "formal")).isZero();
        assertThat(ClosetService.mentionsRain("오후에 비 와요")).isTrue();
        assertThat(ClosetService.mentionsRain("우산 챙겨야 하는 날")).isTrue();
        assertThat(ClosetService.mentionsRain("친구 결혼식, 체감 15도")).isFalse();
        assertThat(ClosetService.mentionsRain("비즈니스 미팅")).isFalse();
    }

    @Test
    @DisplayName("최근 입은 옷 감점: 어제 > 그제 > 일주일 안, 오늘·오래전은 없음")
    void recency() {
        var today = java.time.LocalDate.parse("2026-11-04");
        assertThat(CandidateFilter.recencyPenalty(today.minusDays(1), today)).isEqualTo(0.06);
        assertThat(CandidateFilter.recencyPenalty(today.minusDays(2), today)).isEqualTo(0.04);
        assertThat(CandidateFilter.recencyPenalty(today.minusDays(7), today)).isEqualTo(0.02);
        assertThat(CandidateFilter.recencyPenalty(today.minusDays(8), today)).isZero();
        assertThat(CandidateFilter.recencyPenalty(today, today)).isZero();
        assertThat(CandidateFilter.recencyPenalty(null, today)).isZero();
    }

    @Test
    @DisplayName("diversify: 가까운 순을 지키며 종류를 돌아가며 고른다")
    void diversify() {
        var ranked = List.of(g("t1", "top", "a", "gray", "smart", null, 0.9), g("t2", "top", "b", "gray", "smart", null, 0.85),
                g("t3", "top", "c", "gray", "smart", null, 0.8), g("b1", "bottom", "d", "gray", "smart", null, 0.7),
                g("o1", "outer", "e", "gray", "smart", null, 0.6));
        assertThat(ClosetService.diversify(ranked, 3)).extracting(ClosetService.CandidateOut::garmentId).containsExactly("t1", "b1", "o1");
        assertThat(ClosetService.diversify(ranked, 10)).extracting(ClosetService.CandidateOut::garmentId)
                .containsExactly("t1", "b1", "o1", "t2", "t3");
        assertThat(ClosetService.diversify(List.of(), 5)).isEmpty();
    }
}
