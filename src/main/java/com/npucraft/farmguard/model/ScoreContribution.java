package com.npucraft.farmguard.model;

public final class ScoreContribution {

    private final String label;
    private final double points;

    public ScoreContribution(String label, double points) {
        this.label = label;
        this.points = points;
    }

    public String label() {
        return label;
    }

    public double points() {
        return points;
    }

    @Override
    public String toString() {
        String sign = points >= 0 ? "+" : "";
        return label + " " + sign + format(points);
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format("%.1f", value);
    }
}
