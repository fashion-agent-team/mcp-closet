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

    /** pgvector가 돌려준 '[0.1,0.2,…]'. null이면 null. */
    public static float[] parse(String literal) {
        if (literal == null) {
            return null;
        }
        String body = literal.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    /** 길이 1로. 길이가 0이면 null. */
    public static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        n = Math.sqrt(n);
        if (n == 0 || Double.isNaN(n)) {
            return null;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }

    public static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (double) a[i] * b[i];
        }
        return s;
    }
}
