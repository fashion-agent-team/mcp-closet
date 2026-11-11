package com.fashionagent.closet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 옷 사진 벡터(clothes_inspect garments[].embedding) 두 개가 같은 옷인지 본다.
 *
 * <p>보정 행렬(WCCN)이 있으면 (v − mu)·W 로 바꾼 뒤 비교한다. 같은 옷인데 사진마다 달라지는 방향을 줄여서
 * DeepFashion 측정에서 같은 옷 맞게 합침 72% → 86%(새 옷 잘못 합침 5% 기준)였다. 파일은 float32 리틀 엔디언으로
 * mu(d개) 다음 W(d×d, 행 우선). 없으면 그냥 코사인 유사도.
 */
@Component
public class ImageMatcher {

    private static final Logger log = LoggerFactory.getLogger(ImageMatcher.class);

    private final int dim;
    private final float[] mu;
    private final float[][] w;
    private final double threshold;

    public ImageMatcher(ClosetProperties props) {
        var p = props.image();
        this.dim = p.dimensions();
        float[][] loaded = load(p.adapterPath(), dim);
        if (loaded == null) {
            this.mu = null;
            this.w = null;
            this.threshold = p.threshold();
        } else {
            this.mu = loaded[0];
            this.w = java.util.Arrays.copyOfRange(loaded, 1, loaded.length);
            this.threshold = p.adapterThreshold();
            log.info("옷 사진 보정 행렬을 씁니다({}), 기준 {}", p.adapterPath(), threshold);
        }
    }

    public int dimensions() {
        return dim;
    }

    public double threshold() {
        return threshold;
    }

    public boolean adapted() {
        return w != null;
    }

    /** 받은 숫자 목록을 길이 1 벡터로. 길이가 다르거나 숫자가 아니면 null(그 옷은 사진 없이 저장). */
    public float[] accept(List<Double> embedding) {
        if (embedding == null || embedding.size() != dim) {
            return null;
        }
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            Double x = embedding.get(i);
            if (x == null || x.isNaN() || x.isInfinite()) {
                return null;
            }
            v[i] = x.floatValue();
        }
        return Vectors.normalize(v);
    }

    /** 비교용 벡터. 저장된 평균은 길이가 1이 아니라서 먼저 맞춘다. */
    public float[] key(float[] v) {
        float[] n = Vectors.normalize(v);
        if (n == null || w == null) {
            return n;
        }
        float[] c = new float[dim];
        for (int i = 0; i < dim; i++) {
            c[i] = n[i] - mu[i];
        }
        float[] out = new float[dim];
        for (int i = 0; i < dim; i++) {
            float ci = c[i];
            if (ci == 0) {
                continue;
            }
            float[] row = w[i];
            for (int j = 0; j < dim; j++) {
                out[j] += ci * row[j];
            }
        }
        return Vectors.normalize(out);
    }

    /** 평균 갱신: (old·n + v) / (n+1). n은 maxCount에서 멈춘다. */
    static float[] mean(float[] old, int count, float[] v, int maxCount) {
        if (old == null || count <= 0) {
            return v.clone();
        }
        int n = Math.min(count, maxCount - 1);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (old[i] * n + v[i]) / (n + 1);
        }
        return out;
    }

    private static float[][] load(String path, int dim) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String where = path.trim();
        long want = 4L * dim * (dim + 1);
        try (InputStream in = open(where)) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length != want) {
                throw new IllegalStateException("옷 사진 보정 행렬 크기가 달라요: " + bytes.length + "바이트, " + want + "바이트여야 함");
            }
            var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[][] out = new float[dim + 1][dim];
            for (float[] row : out) {
                buf.get(row);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("옷 사진 보정 행렬을 읽지 못했어요: " + where, e);
        }
    }

    /** classpath:로 시작하면 jar 안(기본 보정 행렬), 아니면 파일 경로. */
    private static InputStream open(String where) throws IOException {
        if (where.startsWith("classpath:")) {
            String name = where.substring("classpath:".length()).replaceFirst("^/", "");
            InputStream in = ImageMatcher.class.getClassLoader().getResourceAsStream(name);
            if (in == null) {
                throw new IOException("없음: " + where);
            }
            return in;
        }
        return Files.newInputStream(Path.of(where));
    }
}
