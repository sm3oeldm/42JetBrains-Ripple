package com.shop;

/**
 * The shared utility at the centre of the demo.
 *
 * Four different places call applyDiscount(). Two of them are covered by tests,
 * two are not. Edit this method and Ripple shows you all four, and tells you
 * which two nothing is protecting.
 */
public final class PriceCalculator {

    private PriceCalculator() {
    }

    /**
     * Sum the prices, then take a percentage off the total.
     *
     * BUG: the discount is applied INSIDE the loop, so it compounds once per
     * item instead of being taken once at the end.
     *
     * 100 + 50 + 25 at 10% off should charge 157.50. It charges 135.90 - the
     * shop gives away 21.60 on this basket. (Not 1-0.9^3: the first price is
     * discounted three times, the second twice, the third once, so the effective
     * discount is 22.3% of the raw 175.00, not 10% and not 27%.)
     *
     * No exception is thrown. The number is just quietly wrong, which is exactly
     * why a value trail is the only way to see it.
     */
    public static double applyDiscount(double[] prices, double pct) {
        double total = 0;
        for (int i = 0; i < prices.length; i++) {
            total += prices[i];
            total = total * (1 - pct);
        }
        return round(total);
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
