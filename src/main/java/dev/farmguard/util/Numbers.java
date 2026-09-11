package dev.farmguard.util;

public final class Numbers {

    private Numbers() {
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double pearson(double[] x, double[] y, int n) {
        if (x == null || y == null || n < 3 || x.length < n || y.length < n) {
            return 0.0;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXX = 0.0;
        double sumYY = 0.0;
        double sumXY = 0.0;
        for (int i = 0; i < n; i++) {
            double xv = x[i];
            double yv = y[i];
            sumX += xv;
            sumY += yv;
            sumXX += xv * xv;
            sumYY += yv * yv;
            sumXY += xv * yv;
        }
        double covariance = sumXY - (sumX * sumY / n);
        double varianceX = sumXX - (sumX * sumX / n);
        double varianceY = sumYY - (sumY * sumY / n);
        if (varianceX <= 1.0e-9 || varianceY <= 1.0e-9) {
            return 0.0;
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    public static String oneDecimal(double value) {
        return String.format("%.1f", value);
    }
}
