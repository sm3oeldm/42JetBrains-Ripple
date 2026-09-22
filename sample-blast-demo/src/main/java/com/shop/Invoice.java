package com.shop;

/** Caller #2 — covered by InvoiceTest. */
public class Invoice {
    private final double[] lineItems;

    public Invoice(double[] lineItems) {
        this.lineItems = lineItems;
    }

    public double amountDue(double bulkDiscount) {
        return PriceCalculator.applyDiscount(lineItems, bulkDiscount);
    }
}
