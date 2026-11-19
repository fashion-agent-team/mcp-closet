package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SameGarmentTest {

    @Test
    void tone() {
        assertThat(SameGarment.toneConflicts("light", "dark")).isTrue();
        assertThat(SameGarment.toneConflicts("dark", "mid")).isFalse();
        assertThat(SameGarment.toneConflicts("dark", "dark")).isFalse();
        assertThat(SameGarment.toneConflicts(null, "dark")).isFalse();
    }

    @Test
    void pattern() {
        assertThat(SameGarment.patternConflicts("스트라이프", "무지")).isTrue();
        assertThat(SameGarment.patternConflicts("스트라이프", "체크")).isTrue();
        assertThat(SameGarment.patternConflicts("가로 줄무늬", "스트라이프")).isFalse();
        assertThat(SameGarment.patternConflicts("무지", "단색")).isFalse();
        assertThat(SameGarment.patternConflicts("깅엄 체크", "체크")).isFalse();
        // 모르면(null) 다르다고 보지 않는다
        assertThat(SameGarment.patternConflicts(null, "스트라이프")).isFalse();
    }
}
