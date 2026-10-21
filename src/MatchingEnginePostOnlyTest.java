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
    Sequencer sequencer = new Sequencer();

    // Step 3B: requests reach the engine only as stamped commands. Ids start at 1, as before.
    Response submit(OrderRequest request) {
        return engine.submitNewOrderCommand(sequencer.createStampedNewOrderCommand(request));
    }

    CancelResult cancel(long orderId) {
        return engine.submitCancelOrderCommand(sequencer.createStampedCancelOrderCommand(orderId));
    }

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
            Response response = submit(postOnly(Side.BUY, 100, 10));     // id 1

            assertEquals(1, response.orderId());
            assertTrue(response.trades().isEmpty());
            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertEquals(10, orderBook.getVolume(Side.BUY, 100));
        }

        @Test
        void oneTickAwayFromTheBestAsk() {
            submit(limit(Side.SELL, 101, 10));                           // id 1

            Response response = submit(postOnly(Side.BUY, 100, 10));     // id 2

            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertTrue(orderBook.containsOrder(2));
            assertEquals(10, orderBook.getVolume(Side.SELL, 101));              // Untouched.
        }

        @Test
        void sellOneTickAwayFromTheBestBid() {
            submit(limit(Side.BUY, 100, 10));                            // id 1

            Response response = submit(postOnly(Side.SELL, 101, 10));    // id 2

            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(10, orderBook.getVolume(Side.SELL, 101));
        }

        @Test
        void ownSideDoesNotCount() {
            submit(limit(Side.BUY, 100, 10));                            // id 1: same side, better price

            Response response = submit(postOnly(Side.BUY, 99, 10));     // id 2

            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(2, orderBook.getNoOfBids());
        }

        @Test
        void restedPostOnlyIsMatchedLaterAtItsOwnPrice() {
            submit(postOnly(Side.BUY, 100, 10));                         // id 1 rests

            Response sell = submit(limit(Side.SELL, 99, 4));             // id 2

            assertEquals(1, sell.trades().size());
            assertEquals(1, sell.trades().get(0).getBuyOrderId());
            assertEquals(100, sell.trades().get(0).getPrice());                 // Resting price.
            assertEquals(6, orderBook.getVolume(Side.BUY, 100));
        }

        @Test
        void restedPostOnlyQueuesBehindEarlierOrders() {
            submit(limit(Side.BUY, 100, 5));                             // id 1
            submit(postOnly(Side.BUY, 100, 5));                          // id 2

            Response sell = submit(limit(Side.SELL, 100, 5));            // id 3

            assertEquals(1, sell.trades().get(0).getBuyOrderId());              // id 1 was first.
            assertTrue(orderBook.containsOrder(2));
        }

        @Test
        void restedPostOnlyCanBeCancelled() {
            Response response = submit(postOnly(Side.BUY, 100, 10));

            CancelResult result = cancel(response.orderId());

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(10, result.cancelledQuantity());
            assertEquals(0, orderBook.getNoOfBids());
        }
    }

    @Nested
    class Rejected {

        @Test
        void priceEqualToBestAsk() {
            submit(limit(Side.SELL, 100, 10));                           // id 1

            Response response = submit(postOnly(Side.BUY, 100, 10));     // id 2

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
            submit(limit(Side.SELL, 100, 5));                            // id 1
            submit(limit(Side.SELL, 103, 5));                            // id 2

            Response response = submit(postOnly(Side.BUY, 105, 10));     // id 3

            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertTrue(response.trades().isEmpty());
            assertEquals(5, orderBook.getVolume(Side.SELL, 100));
            assertEquals(5, orderBook.getVolume(Side.SELL, 103));
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void sellPriceEqualToBestBid() {
            submit(limit(Side.BUY, 100, 10));                            // id 1

            Response response = submit(postOnly(Side.SELL, 100, 10));    // id 2

            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertEquals(10, orderBook.getVolume(Side.BUY, 100));
            assertEquals(0, orderBook.getNoOfAsks());
        }

        @Test
        void evenOneUnitOfOppositeVolumeIsEnough() {
            submit(limit(Side.SELL, 100, 1));                            // id 1

            Response response = submit(postOnly(Side.BUY, 100, 10));     // id 2

            assertEquals(OrderStatus.REJECTED, response.orderStatus());
            assertEquals(1, orderBook.getVolume(Side.SELL, 100));
        }

        @Test
        void rejectedPostOnlyCannotBeCancelled() {
            submit(limit(Side.SELL, 100, 10));                           // id 1
            Response response = submit(postOnly(Side.BUY, 100, 10));     // id 2

            CancelResult result = cancel(response.orderId());

            assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
            assertEquals(0, result.cancelledQuantity());
        }

        @Test
        void rejectedPostOnlyStillUsesUpItsId() {
            submit(limit(Side.SELL, 100, 10));                           // id 1
            submit(postOnly(Side.BUY, 100, 10));                         // id 2: rejected

            Response next = submit(limit(Side.BUY, 90, 10));

            assertEquals(3, next.orderId());                                    // Never reused (D16).
        }
    }
}
