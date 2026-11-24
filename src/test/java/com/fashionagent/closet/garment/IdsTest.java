package com.fashionagent.closet.garment;

import com.fashionagent.closet.ClosetException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    @DisplayName("garmentId는 c_ + URL에 안전한 12글자이고 1000번 만들어도 겹치지 않는다")
    void garmentIds() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = Ids.garmentId();
            assertThat(id).matches("c_[A-Za-z0-9_-]{12}");
            seen.add(id);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("userId Given UUID(앞뒤 공백 포함) When 읽으면 Then UUID")
    void validUserId() {
        UUID id = UUID.randomUUID();
        assertThat(Ids.userId(" " + id + " ")).isEqualTo(id);
    }

    @Test
    @DisplayName("userId Given 비었거나 UUID가 아님 When 읽으면 Then bad_request")
    void invalidUserId() {
        for (String bad : new String[] {null, "", "  ", "u1", "dev-user"}) {
            assertThatThrownBy(() -> Ids.userId(bad))
                    .isInstanceOf(ClosetException.class)
                    .extracting(e -> ((ClosetException) e).code())
                    .isEqualTo("bad_request");
        }
    }
}
