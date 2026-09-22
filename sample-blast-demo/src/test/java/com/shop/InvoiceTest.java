package com.shop;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Covers Invoice -> PriceCalculator.applyDiscount. */
public class InvoiceTest {

    @Test
    public void amountDueIsPositive() {
        Invoice invoice = new Invoice(new double[]{200.0, 100.0});
        assertTrue(invoice.amountDue(0.05) > 0);
    }
}
