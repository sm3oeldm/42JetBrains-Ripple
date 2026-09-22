package com.shop;

/** Caller #4 — NOTHING TESTS THIS EITHER. Goes straight to the customer. */
public class Receipt {
    public String render(double[] prices, double discount) {
        double total = PriceCalculator.applyDiscount(prices, discount);
        return "TOTAL: " + total;
    }
}
