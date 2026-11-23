package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 임베딩 문장. 옷 설명과 상황 문장이 같은 한국어 이름을 써야 검색이 맞는다. */
class LabelsTest {

    @Test
    @DisplayName("Given 약속된 값 When 이름으로 바꾸면 Then 한국어, 모르는 값은 기타·중간 톤·격식 모름")
    void names() {
        assertThat(Labels.category("outer")).isEqualTo("겉옷");
        assertThat(Labels.category("hat")).isEqualTo("기타");
        assertThat(Labels.tone("dark")).isEqualTo("어두운 톤");
        assertThat(Labels.tone("??")).isEqualTo("중간 톤");
        assertThat(Labels.formality("sporty")).isEqualTo("운동복, 활동적인 날");
        assertThat(Labels.formality("unknown")).isEqualTo("격식 모름");
    }

    @Test
    @DisplayName("Given 무늬·소재·메모가 있는 옷 When passage를 만들면 Then e5 머리말 passage: 와 모든 값이 들어간다")
    void fullPassage() {
        var g = new Garment.Normalized("top", "셔츠", "하늘색", "blue", "light", "smart", "줄무늬", "면", "단추 있음");

        assertThat(Labels.passage(g)).isEqualTo(
                "passage: 상의 셔츠. 색: 하늘색 (밝은 톤). 격식: 단정한 옷, 출근·모임. 무늬: 줄무늬. 소재: 면. 단추 있음");
    }

    @Test
    @DisplayName("Given 무늬·소재·메모가 없는 옷 When passage를 만들면 Then 그 부분은 빠지고 null 글자가 안 나온다")
    void minimalPassage() {
        var g = new Garment.Normalized("bottom", "청바지", "남색", "blue", "dark", "casual", null, null, null);

        String p = Labels.passage(g);

        assertThat(p).isEqualTo("passage: 하의 청바지. 색: 남색 (어두운 톤). 격식: 편한 옷, 일상.");
        assertThat(p).doesNotContain("null").doesNotContain("무늬");
    }
}
