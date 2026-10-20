import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/*

OrderRequest is the gateway-side (stateless) check: it rejects bad input before it reaches the engine.
It must use the SAME limits as Order, taken from Order's constants:
- everything OrderRequest accepts, Order must accept   (otherwise the engine throws on a "valid" request)
- everything Order rejects, OrderRequest must reject   (otherwise a bad request gets past the gateway)

Boundaries tested for each limit: below min, min, max, above max.

 */
class OrderRequestTest {

    static OrderRequest request(long price, long quantity) {
        return OrderRequest.limit(Side.BUY, price, quantity);
    }

    @Nested
    class SideCheck {

        @Test
        void buyAndSellAreAccepted() {
            assertEquals(Side.BUY, OrderRequest.limit(Side.BUY, 100, 10).getSide());
            assertEquals(Side.SELL, OrderRequest.limit(Side.SELL, 100, 10).getSide());
        }

        @Test
        void nullSideIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> OrderRequest.limit(null, 100, 10));
        }
    }

    @Nested
    class Price {

        @Test
        void minPriceIsAccepted() {
            assertEquals(1, request(1, 10).getPrice());
        }

        @Test
        void maxPriceIsAccepted() {
            assertEquals(Order.MAX_PRICE, request(Order.MAX_PRICE, 10).getPrice());
        }

        @Test
        void zeroPriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(0, 10));
        }

        @Test
        void negativePriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(-1, 10));
        }

        @Test
        void priceAboveMaxIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(Order.MAX_PRICE + 1, 10));
        }

        @Test
        void longMaxPriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(Long.MAX_VALUE, 10));
        }
    }

    @Nested
    class Quantity {

        @Test
        void minQuantityIsAccepted() {
            assertEquals(1, request(100, 1).getQuantity());
        }

        @Test
        void maxQuantityIsAccepted() {
            assertEquals(Order.MAX_ORDER_QUANTITY, request(100, Order.MAX_ORDER_QUANTITY).getQuantity());
        }

        @Test
        void zeroQuantityIsRejected() {
            // The bug from the v3 open threads: OrderRequest used to accept 0.
            assertThrows(IllegalArgumentException.class, () -> request(100, 0));
        }

        @Test
        void negativeQuantityIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(100, -1));
        }

        @Test
        void quantityAboveMaxIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(100, Order.MAX_ORDER_QUANTITY + 1));
        }

        @Test
        void longMaxQuantityIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> request(100, Long.MAX_VALUE));
        }
    }

    @Nested
    class AgreesWithOrder {

        @Test
        void requestAtBothMaxLimitsBuildsAValidOrder() {
            // The gateway and the invariant must agree exactly at the boundary.
            OrderRequest req = request(Order.MAX_PRICE, Order.MAX_ORDER_QUANTITY);

            assertDoesNotThrow(() ->
                    new Order(1, req.getSide(), req.getPrice(), req.getQuantity(), Instant.EPOCH, OrderType.LIMIT));
        }

        @Test
        void requestAtBothMinLimitsBuildsAValidOrder() {
            OrderRequest req = request(1, 1);

            assertDoesNotThrow(() ->
                    new Order(1, req.getSide(), req.getPrice(), req.getQuantity(), Instant.EPOCH, OrderType.LIMIT));
        }

        @Test
        void maxLimitRequestIsSubmittedThroughTheEngine() {
            // End to end: a request at the limits reaches the book without the engine throwing.
            OrderBook book = new OrderBook();
            MatchingEngine engine = new MatchingEngine(book);

            assertTrue(engine.submit(request(Order.MAX_PRICE, Order.MAX_ORDER_QUANTITY)).trades().isEmpty());
            assertEquals(Order.MAX_ORDER_QUANTITY, book.getVolume(Side.BUY, Order.MAX_PRICE));
        }
    }
}
