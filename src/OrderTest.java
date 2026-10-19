import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/*

The Order constructor holds the REAL invariant: an invalid Order must be impossible, no matter who builds it
(OrderRequest, tests, replay). So these tests build Orders directly, bypassing OrderRequest.

Limits under test:
- 1 <= price    <= Order.MAX_PRICE
- 1 <= quantity <= Order.MAX_ORDER_QUANTITY
- MAX_PRICE * MAX_ORDER_QUANTITY fits in a long (notional = price * quantity cannot overflow).

 */
class OrderTest {

    static Order order(long id, long price, long quantity) {
        return new Order(id, Side.BUY, price, quantity, Instant.EPOCH, OrderType.LIMIT);
    }

    @Nested
    class Limits {

        @Test
        void maxNotionalFitsInALong() {
            // Math.multiplyExact throws ArithmeticException on overflow instead of silently wrapping.
            assertDoesNotThrow(() -> Math.multiplyExact(Order.MAX_PRICE, Order.MAX_ORDER_QUANTITY));
        }

        @Test
        void maxPriceIsTheLargestPriceWhoseNotionalFitsInALong() {
            // Pins the derivation MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY.
            // Drop this test if you deliberately choose a smaller, rounder MAX_PRICE.
            assertThrows(ArithmeticException.class,
                    () -> Math.multiplyExact(Order.MAX_PRICE + 1, Order.MAX_ORDER_QUANTITY));
        }
    }

    @Nested
    class Price {

        @Test
        void minPriceIsAccepted() {
            assertEquals(1, order(1, 1, 10).getPrice());
        }

        @Test
        void maxPriceIsAccepted() {
            assertEquals(Order.MAX_PRICE, order(1, Order.MAX_PRICE, 10).getPrice());
        }

        @Test
        void zeroPriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, 0, 10));
        }

        @Test
        void negativePriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, -1, 10));
        }

        @Test
        void priceAboveMaxIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, Order.MAX_PRICE + 1, 10));
        }

        @Test
        void longMaxPriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, Long.MAX_VALUE, 10));
        }
    }

    @Nested
    class Quantity {

        @Test
        void minQuantityIsAccepted() {
            assertEquals(1, order(1, 100, 1).getOriginalQuantity());
        }

        @Test
        void maxQuantityIsAccepted() {
            assertEquals(Order.MAX_ORDER_QUANTITY, order(1, 100, Order.MAX_ORDER_QUANTITY).getOriginalQuantity());
        }

        @Test
        void zeroQuantityIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, 100, 0));
        }

        @Test
        void negativeQuantityIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, 100, -1));
        }

        @Test
        void quantityAboveMaxIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(1, 100, Order.MAX_ORDER_QUANTITY + 1));
        }
    }

    @Nested
    class OrderId {

        @Test
        void zeroIdIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(0, 100, 10));
        }

        @Test
        void negativeIdIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> order(-1, 100, 10));
        }
    }

    @Nested
    class Fill {

        @Test
        void newOrderHasNothingFilled() {
            Order o = order(1, 100, 10);
            assertEquals(0, o.getFilledQuantity());
            assertEquals(10, o.getRemainingQuantity());
        }

        @Test
        void partialFillsAccumulate() {
            Order o = order(1, 100, 10);
            o.fill(3);
            o.fill(4);

            assertEquals(7, o.getFilledQuantity());
            assertEquals(3, o.getRemainingQuantity());
            assertEquals(10, o.getOriginalQuantity());
        }

        @Test
        void fillToExactlyZeroRemaining() {
            Order o = order(1, 100, 10);
            o.fill(10);
            assertEquals(0, o.getRemainingQuantity());
        }

        @Test
        void overfillIsRejectedAndOrderUnchanged() {
            Order o = order(1, 100, 10);
            o.fill(6);

            assertThrows(IllegalArgumentException.class, () -> o.fill(5));
            assertEquals(4, o.getRemainingQuantity());
        }

        @Test
        void nonPositiveFillIsRejected() {
            Order o = order(1, 100, 10);
            assertThrows(IllegalArgumentException.class, () -> o.fill(0));
            assertThrows(IllegalArgumentException.class, () -> o.fill(-1));
            assertEquals(10, o.getRemainingQuantity());
        }
    }
}
