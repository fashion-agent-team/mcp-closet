package com.fashionagent.closet.embedding;

/** e5 접두어. 저장하는 글은 "passage: ", 찾는 글은 "query: "(모델 카드 권장). */
public final class E5 {

    private E5() {
    }

    public static String passage(String text) {
        return text.startsWith("passage: ") ? text : "passage: " + text;
    }

    public static String query(String text) {
        return text.startsWith("query: ") ? text : "query: " + text;
    }
}
