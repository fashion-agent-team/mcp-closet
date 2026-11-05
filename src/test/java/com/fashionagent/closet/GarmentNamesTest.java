package com.fashionagent.closet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GarmentNamesTest {

    @Test
    @DisplayName("같은 이름·동의어·꾸밈말 붙은 이름은 같은 옷 이름, 다른 종류나 반팔/긴팔이 다르면 아님")
    void sameKind() {
        assertThat(GarmentNames.sameKind("니트", "니트")).isTrue();
        assertThat(GarmentNames.sameKind("니트", "스웨터")).isTrue();
        assertThat(GarmentNames.sameKind("니트", "니트 스웨터")).isTrue();
        assertThat(GarmentNames.sameKind("자켓", "재킷")).isTrue();
        assertThat(GarmentNames.sameKind("반팔 티셔츠", "티셔츠")).isTrue();
        assertThat(GarmentNames.sameKind("체크 셔츠", "셔츠")).isTrue();

        assertThat(GarmentNames.sameKind("니트", "가디건")).isFalse();
        assertThat(GarmentNames.sameKind("니트", "후드티")).isFalse();
        assertThat(GarmentNames.sameKind("반팔 티셔츠", "긴팔 티셔츠")).isFalse();
        assertThat(GarmentNames.sameKind("셔츠", "블라우스")).isFalse();
    }
}
