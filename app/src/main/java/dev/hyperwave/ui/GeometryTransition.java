package dev.hyperwave.ui;

import java.util.function.DoubleUnaryOperator;

/** Geometry transitions retain directly displayed fallback frames when interrupted. */
final class GeometryTransition {
    static final int COMPONENTS = 24;
    final float[] shown = new float[COMPONENTS];
    private final float[] start = new float[COMPONENTS];
    private final float[] target = new float[COMPONENTS];
    private final DoubleUnaryOperator liveCurve;
    private final DoubleUnaryOperator pauseCurve;
    private long started;
    private long duration;
    private boolean direct;

    GeometryTransition(DoubleUnaryOperator liveCurve, DoubleUnaryOperator pauseCurve) {
        this.liveCurve = liveCurve;
        this.pauseCurve = pauseCurve;
    }

    void reset(float[] geometry) {
        System.arraycopy(geometry, 0, shown, 0, COMPONENTS);
        System.arraycopy(geometry, 0, start, 0, COMPONENTS);
        System.arraycopy(geometry, 0, target, 0, COMPONENTS);
        direct = false;
        duration = 0;
    }

    void display(float[] geometry) {
        System.arraycopy(geometry, 0, shown, 0, COMPONENTS);
        direct = true;
    }

    void transition(float[] geometry, long now, long milliseconds) {
        evaluate(now);
        System.arraycopy(shown, 0, start, 0, COMPONENTS);
        System.arraycopy(geometry, 0, target, 0, COMPONENTS);
        started = now;
        duration = milliseconds;
        direct = false;
    }

    void evaluate(long now) {
        if (direct) return;
        float fraction = duration == 0 ? 1 : Math.max(0, Math.min(1, (now - started) / (float) duration));
        float eased = (float) (duration == 900 ? pauseCurve : liveCurve).applyAsDouble(fraction);
        for (int i = 0; i < COMPONENTS; i++) shown[i] = start[i] + (target[i] - start[i]) * eased;
    }

    boolean animating(long now) { return !direct && now - started < duration; }
}
