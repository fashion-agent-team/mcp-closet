package com.fashionagent.closet;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** 옷 id("c_" + 임의 문자열)와 userId 확인. */
final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Ids() {
    }

    static String garmentId() {
        byte[] buf = new byte[9];
        RANDOM.nextBytes(buf);
        return "c_" + URL.encodeToString(buf);
    }

    /** userId는 게이트웨이가 준 UUID여야 한다(agent가 X-User-Id 값으로 덮어써서 넣음). */
    static UUID userId(String value) {
        if (value == null || value.isBlank()) {
            throw ClosetException.badRequest("userId가 없어요.");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw ClosetException.badRequest("userId는 UUID여야 해요.");
        }
    }
}
