import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 2B4: post-only.
- Would match anything on arrival (price crosses the best opposite level, equal price included)
  -> REJECTED, full quantity, no trades, book unchanged, never rests.
- Otherwise -> RESTED, full quantity, and from then on it is an ordinary resting order.

 */
class MatchingEnginePostOnlyTest {

    OrderBook orderBook;
    MatchingEngine engine;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
        engine = new MatchingEngine(orderBook);
    }

    static OrderRequest limit(Side side, long price, long quantity) {
        return OrderRequest.limit(side, price, quantity);
    }

    static OrderRequest postOnly(Side side, long price, long quantity) {
        return new OrderRequest(side, price, quantity, OrderType.POST_ONLY);
    }

    @Nested
    class Rests {

        @Test
        void onEmptyBook() {
            Response response = engine.submit(postOnly(Side.BUY, 100, 10));     // id 1

            assertEquals(1, response.orderId());
            assertTrue(response.trades().isEmpty());
            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertEquals(10, orderBook.getVolume(Side.BUY, 100));
        }

        @Test
        void oneTickAwayFromTheBestAsk() {
            engine.submit(limit(Side.SELL, 101, 10));                           // id 1

            Response response = engine.submit(postOnly(Side.BUY, 100, 10));     // id 2

            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertTrue(orderBook.containsOrder(2));
            assertEquals(10, orderBook.getVolume(Side.SELL, 101));              // Untouched.
        }

        @Test
        void sellOneTickAwayFromTheBestBid() {
            engine.submit(limit(Side.BUY, 100, 10));                            // id 1

            Response response = engine.submit(postOnly(Side.SELL, 101, 10));    // id 2

            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(10, orderBook.getVolume(Side.SELL, 101));
        }

        @Test
        void ownSideDoesNotCount() {
            engine.submit(limit(Side.BUY, 100, 10));                            // id 1: same side, better price

            Response response = engine.submit(postOnly(Side.BUY, 99, 10));     // id 2

            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(2, orderBook.getNoOfBids());
        }

        @Test
        void restedPostOnlyIsMatchedLaterAtItsOwnPrice() {
            engine.submit(postOnly(Side.BUY, 100, 10));                         // id 1 rests

            Response sell = engine.submit(limit(Side.SELL, 99, 4));             // id 2

            assertEquals(1, sell.trades().size());
            assertEquals(1, sell.trades().get(0).getBuyOrderId());
            assertEquals(100, sell.trades().get(0).getPrice());                 // Resting price.
            assertEquals(6, orderBook.getVolume(Side.BUY, 100));
        }

        @Test
        void restedPostOnlyQueuesBehindEarlierOrders() {
            engine.submit(limit(Side.BUY, 100, 5));                             // id 1
            engine.submit(postOnly(Side.BUY, 100, 5));                          // id 2

            Response sell = engine.submit(limit(Side.SELL, 100, 5));            // id 3

            assertEquals(1, sell.trades().get(0).getBuyOrderId());              // id 1 was first.
            assertTrue(orderBook.containsOrder(2));
        }

        @Test
        void restedPostOnlyCanBeCancelled() {
            Response response = engine.submit(postOnly(Side.BUY, 100, 10));

            CancelResult result = engine.cancel(response.orderId());

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(10, result.cancelledQuantity());
            assertEquals(0, orderBook.getNoOfBids());
        }
    }

    @Nested
    class Rejected {

        @Test
        void priceEqualToBestAsk() {
            engine.submit(limit(Side.SELL, 100, 10));                           // id 1

            Response response = engine.submit(postOnly(Side.BUY, 100, 10));     // id 2

            assertEquals(2, response.orderId());
            assertTrue(response.trades().isEmpty());
            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertEquals(10, orderBook.getVolume(Side.SELL, 100));              // Book unchanged.
            assertFalse(orderBook.containsOrder(2));                            // Never rests.
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void priceThroughBestAsk() {
            engine.submit(limit(Side.SELL, 100, 5));                            // id 1
            engine.submit(limit(Side.SELL, 103, 5));                            // id 2

            Response response = engine.submit(postOnly(Side.BUY, 105, 10));     // id 3

            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertTrue(response.trades().isEmpty());
            assertEquals(5, orderBook.getVolume(Side.SELL, 100));
            assertEquals(5, orderBook.getVolume(Side.SELL, 103));
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void sellPriceEqualToBestBid() {
            engine.submit(limit(Side.BUY, 100, 10));                            // id 1

            Response response = engine.submit(postOnly(Side.SELL, 100, 10));    // id 2

            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertEquals(10, orderBook.getVolume(Side.BUY, 100));
            assertEquals(0, orderBook.getNoOfAsks());
        }

        @Test
        void evenOneUnitOfOppositeVolumeIsEnough() {
            engine.submit(limit(Side.SELL, 100, 1));                            // id 1

            Response response = engine.submit(postOnly(Side.BUY, 100, 10));     // id 2

            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertEquals(1, orderBook.getVolume(Side.SELL, 100));
        }

        @Test
        void rejectedPostOnlyCannotBeCancelled() {
            engine.submit(limit(Side.SELL, 100, 10));                           // id 1
            Response response = engine.submit(postOnly(Side.BUY, 100, 10));     // id 2

            CancelResult result = engine.cancel(response.orderId());

            assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
            assertEquals(0, result.cancelledQuantity());
        }

        @Test
        void rejectedPostOnlyStillUsesUpItsId() {
            engine.submit(limit(Side.SELL, 100, 10));                           // id 1
            engine.submit(postOnly(Side.BUY, 100, 10));                         // id 2: rejected

            Response next = engine.submit(limit(Side.BUY, 90, 10));

            assertEquals(3, next.orderId());                                    // Never reused (D16).
        }
    }
}
