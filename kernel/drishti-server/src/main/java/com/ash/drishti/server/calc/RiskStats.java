/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.calc;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small risk statistics Calc asks the server for (the server computes every risk number; the browser only displays):
 * Kupiec's proportion-of-failures test, maximum drawdown, the Sharpe ratio, the Herfindahl-Hirschman index and the two
 * FRTB sensitivities-based aggregation formulas. Pure functions over the request's numbers.
 */
public final class RiskStats {

    private RiskStats() {
    }

    /** Runs one statistic by name over a JSON body of its arguments. */
    public static Map<String, Object> run(String fn, JsonNode a) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (fn) {
            case "kupiec-pof" -> out.putAll(kupiec(a.path("exceptions").asInt(), a.path("n").asInt(),
                    a.path("confidence").asDouble(0.99)));
            case "max-drawdown" -> out.putAll(maxDrawdown(vec(a, "values")));
            case "sharpe" -> out.put("value", sharpe(vec(a, "pnl"), a.path("periods").asInt(252)));
            case "hhi" -> out.put("value", hhi(vec(a, "amounts")));
            case "frtb-bucket-charge" -> out.put("value", frtbBucket(vec(a, "ws"), matrix(a.path("rho"), vec(a, "ws").length)));
            case "frtb-across-buckets" -> {
                double[] kb = vec(a, "kb");
                out.put("value", frtbAcross(kb, vec(a, "sb"), matrix(a.path("gamma"), kb.length)));
            }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "no statistic '" + fn
                    + "': kupiec-pof, max-drawdown, sharpe, hhi, frtb-bucket-charge, frtb-across-buckets");
        }
        return out;
    }

    public static Map<String, Object> kupiec(int exceptions, int n, double confidence) {
        double p = 1 - confidence;
        double x = exceptions;
        double phat = n > 0 ? x / n : 0.0;
        double lr = Math.max(-2 * (ll(p, x, n) - ll(phat, x, n)), 0.0);
        double pvalue = erfc(Math.sqrt(lr / 2));
        double scaled = n > 0 ? x * 250 / n : 0;
        String light = scaled <= 4 ? "green" : scaled <= 9 ? "amber" : "red";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exceptions", exceptions);
        m.put("days", n);
        m.put("expected", p * n);
        m.put("lr", lr);
        m.put("pvalue", pvalue);
        m.put("light", light);
        return m;
    }

    private static double ll(double prob, double x, double n) {
        double pr = Math.min(Math.max(prob, 1e-15), 1 - 1e-15);
        return (n - x) * Math.log(1 - pr) + x * Math.log(pr);
    }

    /** Complementary error function, Numerical Recipes erfcc refined by a continued fraction for the tail (1e-15 relative). */
    static double erfc(double x) {
        if (x < 0) {
            return 2 - erfc(-x);
        }
        if (x == 0) {
            return 1.0;
        }
        if (x < 2.5) {                                   // Taylor series of erf
            double sum = x, term = x;
            for (int n = 1; n < 200; n++) {
                term *= -x * x / n;
                double t = term / (2 * n + 1);
                sum += t;
                if (Math.abs(t) < 1e-18 * Math.abs(sum)) {
                    break;
                }
            }
            return 1 - 2 / Math.sqrt(Math.PI) * sum;
        }
        double f = 0;                                    // continued fraction for the tail
        for (int k = 120; k >= 1; k--) {
            f = k / 2.0 / (x + f);
        }
        return Math.exp(-x * x) / Math.sqrt(Math.PI) / (x + f);
    }

    public static Map<String, Object> maxDrawdown(double[] v) {
        if (v.length == 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "max-drawdown needs a series of values");
        }
        double[] dd = new double[v.length];
        double peak = Double.NEGATIVE_INFINITY;
        int trough = 0;
        for (int i = 0; i < v.length; i++) {
            peak = Math.max(peak, v[i]);
            dd[i] = v[i] - peak;
            if (dd[i] < dd[trough]) {
                trough = i;
            }
        }
        int start = 0;
        for (int i = 1; i <= trough; i++) {
            if (v[i] > v[start]) {
                start = i;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drawdown", dd[trough]);
        m.put("peak", start);
        m.put("trough", trough);
        List<Double> series = new ArrayList<>(dd.length);
        for (double d : dd) {
            series.add(d);
        }
        m.put("series", series);
        return m;
    }

    public static double sharpe(double[] x, int periods) {
        if (x.length < 2) {
            return Double.NaN;
        }
        double mean = 0;
        for (double d : x) {
            mean += d;
        }
        mean /= x.length;
        double ss = 0;
        for (double d : x) {
            ss += (d - mean) * (d - mean);
        }
        double s = Math.sqrt(ss / (x.length - 1));
        return s > 0 ? mean / s * Math.sqrt(periods) : Double.NaN;
    }

    public static double hhi(double[] amounts) {
        double sum = 0;
        for (double d : amounts) {
            sum += Math.abs(d);
        }
        if (sum <= 0) {
            return Double.NaN;
        }
        double h = 0;
        for (double d : amounts) {
            double s = Math.abs(d) / sum;
            h += s * s;
        }
        return h;
    }

    public static double frtbBucket(double[] w, double[][] rho) {
        double t = 0;
        for (int i = 0; i < w.length; i++) {
            for (int j = 0; j < w.length; j++) {
                t += (i == j ? 1.0 : rho[i][j]) * w[i] * w[j];
            }
        }
        return Math.sqrt(Math.max(0.0, t));
    }

    public static double frtbAcross(double[] k, double[] s, double[][] g) {
        double total = across(k, s, g);
        if (total < 0) {                                 // the alternative: each S_b capped at +-K_b
            double[] c = new double[s.length];
            for (int i = 0; i < s.length; i++) {
                c[i] = Math.max(-k[i], Math.min(k[i], s[i]));
            }
            total = across(k, c, g);
        }
        return Math.sqrt(Math.max(total, 0.0));
    }

    private static double across(double[] k, double[] s, double[][] g) {
        double t = 0;
        for (int i = 0; i < k.length; i++) {
            t += k[i] * k[i];
            for (int j = 0; j < k.length; j++) {
                if (i != j) {
                    t += g[i][j] * s[i] * s[j];
                }
            }
        }
        return t;
    }

    private static double[] vec(JsonNode a, String name) {
        JsonNode n = a.path(name);
        if (!n.isArray()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' is a list of numbers");
        }
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = n.get(i).asDouble();
        }
        return v;
    }

    /** A correlation given as one number (every pair) or a square matrix of the given size. */
    private static double[][] matrix(JsonNode n, int size) {
        double[][] m = new double[size][size];
        if (n.isNumber()) {
            for (double[] row : m) {
                java.util.Arrays.fill(row, n.asDouble());
            }
            return m;
        }
        if (!n.isArray() || n.size() != size) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the correlation is a number or a " + size + " x " + size + " matrix");
        }
        for (int i = 0; i < size; i++) {
            if (!n.get(i).isArray() || n.get(i).size() != size) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the correlation is a number or a " + size + " x " + size + " matrix");
            }
            for (int j = 0; j < size; j++) {
                m[i][j] = n.get(i).get(j).asDouble();
            }
        }
        return m;
    }
}
