package com.fashionagent.closet;

/** pgvector 글자 표기 '[0.1,0.2,…]'. JDBC로 넘긴 뒤 SQL에서 CAST(:q AS public.vector). */
public final class Vectors {

    private Vectors() {
    }

    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
