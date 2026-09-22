package com.shop;

/**
 * Caller #3 — NOTHING TESTS THIS.
 *
 * It reaches PriceCalculator.applyDiscount through two hops (monthlyTotal ->
 * summarise), so it only shows up if you actually follow the call graph outward
 * rather than looking at direct callers.
 */
public class Report {
    public double monthlyTotal(double[][] weeks, double discount) {
        double sum = 0;
        for (double[] week : weeks) {
            sum += summarise(week, discount);
        }
        return PriceCalculator.round(sum);
    }

    double summarise(double[] week, double discount) {
        return PriceCalculator.applyDiscount(week, discount);
    }
}
