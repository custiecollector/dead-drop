package org.deaddrop.app;

public final class AudioDiagnostics {
    public final int peakPercent;
    public final int rmsPercent;
    public final boolean clipping;
    public final String hint;

    private AudioDiagnostics(int peakPercent, int rmsPercent, boolean clipping, String hint) {
        this.peakPercent = peakPercent;
        this.rmsPercent = rmsPercent;
        this.clipping = clipping;
        this.hint = hint;
    }

    public static AudioDiagnostics analyze(short[] samples, int n) {
        if (samples == null || n <= 0) return new AudioDiagnostics(0, 0, false, "no signal");
        int count = Math.min(n, samples.length);
        int peak = 0;
        int clipSamples = 0;
        double sumSquares = 0.0;
        for (int i = 0; i < count; i++) {
            int abs = Math.abs((int) samples[i]);
            peak = Math.max(peak, abs);
            if (abs > 31500) clipSamples++;
            double v = samples[i] / 32768.0;
            sumSquares += v * v;
        }
        int peakPct = Math.min(100, (int)Math.round(peak * 100.0 / 32767.0));
        int rmsPct = Math.min(100, (int)Math.round(Math.sqrt(sumSquares / Math.max(1, count)) * 100.0));
        boolean clipping = clipSamples > Math.max(3, count / 200);
        return new AudioDiagnostics(peakPct, rmsPct, clipping, hintFor(peakPct, rmsPct, clipping));
    }

    public String shortStatus() {
        return "peak " + peakPercent + "% / rms " + rmsPercent + "% — " + hint;
    }

    private static String hintFor(int peakPct, int rmsPct, boolean clipping) {
        if (clipping || peakPct > 90) return "clipping: lower speaker/radio volume";
        if (peakPct < 3 || rmsPct < 1) return "too quiet: raise volume or move closer";
        if (peakPct < 10) return "weak: use Robust/Ultra profile or raise volume";
        if (rmsPct > 60) return "very loud/noisy: reduce gain if decode fails";
        return "good";
    }
}
