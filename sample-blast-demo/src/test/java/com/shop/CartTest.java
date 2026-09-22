package com.shop;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Covers Cart -> PriceCalculator.applyDiscount.
 *
 * Note this test passes despite the bug: it only asserts the total is lower than
 * the raw sum, which a compounding discount also satisfies. That is realistic —
 * a weak assertion is how this kind of bug survives a test suite.
 */
public class CartTest {

    @Test
    public void discountReducesTotal() {
        Cart cart = new Cart(new double[]{100.0, 50.0});
        assertTrue(cart.checkoutTotal(0.10) < 150.0);
    }
}
