package dev.gautamgupta.gateway.cache;

/** Cosine similarity and the RediSearch cosine-distance conversion. */
public final class Cosine {

    /**
     * Float32 embeddings do not round-trip to the exact double used at the threshold.
     * One thousandth keeps a true 0.92 on the hit side without letting 0.80 through.
     */
    public static final double TOLERANCE = 1.0e-3d;

    private Cosine() {
    }

    public static double similarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * (double) right[i];
            leftNorm += left[i] * (double) left[i];
            rightNorm += right[i] * (double) right[i];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** RediSearch COSINE distance is {@code 1 - cosine}. */
    public static double fromDistance(double distance) {
        return 1.0d - distance;
    }

    public static boolean hits(double similarity, double threshold) {
        return similarity + TOLERANCE >= threshold;
    }
}
