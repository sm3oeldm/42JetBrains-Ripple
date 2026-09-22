package com.shop;

import java.util.Arrays;

/** Entry point the tracer launches. Keep it tiny and obvious. */
public class Main {
    public static void main(String[] args) {
        double[] prices = {100.00, 50.00, 25.00};
        double discount = 0.10;

        System.out.println("prices:   " + Arrays.toString(prices));
        System.out.println("discount: " + (discount * 100) + "%");

        double total = PriceCalculator.applyDiscount(prices, discount);

        System.out.println("charged:  " + total);
        System.out.println("expected: " + PriceCalculator.round((100.00 + 50.00 + 25.00) * 0.90));
    }
}
