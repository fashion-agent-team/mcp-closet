package com.fashionagent.closet.garment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageQueryPhrasesTest {

    @Test
    @DisplayName("imageQuery는 쉼표·or·and로 나눠 옷 이름마다 견준다 (2026-11-15)")
    void splits() {
        assertThat(ClosetService.phrases("leggings, hoodie or sweatshirt and track pants"))
                .containsExactly("leggings", "hoodie", "sweatshirt", "track pants");
        assertThat(ClosetService.phrases("jeans")).containsExactly("jeans");
        assertThat(ClosetService.phrases("a, b, c, d, e, f, g, h, i, j")).hasSize(8);
        assertThat(ClosetService.phrases(" , ")).containsExactly(",");
    }
}
