package com.shop;

/** Caller #1 — covered by CartTest. */
public class Cart {
    private final double[] prices;

    public Cart(double[] prices) {
        this.prices = prices;
    }

    public double checkoutTotal(double memberDiscount) {
        return PriceCalculator.applyDiscount(prices, memberDiscount);
    }
}
