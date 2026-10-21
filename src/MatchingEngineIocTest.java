import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 2B1: the Response from submit, and IOC.
- Response = (orderId, trades, status, remainingQuantity). filled (sum of trades) + remaining = original.
- LIMIT: remainder rests  -> RESTED with that remainder; nothing left -> FILLED, 0.
- IOC:   remainder is cancelled, never rests -> CANCELLED with that remainder; nothing left -> FILLED, 0.
- The returned orderId is what the client uses to cancel a resting order.

 */
class MatchingEngineIocTest {

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

    static OrderRequest ioc(Side side, long price, long quantity) {
        return new OrderRequest(side, price, quantity, OrderType.IOC);
    }

    static long filled(Response response) {
        return response.trades().stream().mapToLong(Trade::getQuantity).sum();
    }

    @Nested
    class LimitResponse {

        @Test
        void restsOnEmptyBook() {
            Response response = submit(limit(Side.BUY, 100, 10));

            assertEquals(1, response.orderId());
            assertTrue(response.trades().isEmpty());
            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertTrue(orderBook.containsOrder(1));
        }

        @Test
        void partialFillRestsTheRemainder() {
            submit(limit(Side.SELL, 100, 4));                        // id 1

            Response response = submit(limit(Side.BUY, 100, 10));   // id 2

            assertEquals(2, response.orderId());
            assertEquals(OrderStatus.RESTED, response.orderStatus());
            assertEquals(6, response.remainingQuantity());
            assertEquals(4, filled(response));                              // 4 + 6 = 10 original.
            assertEquals(6, orderBook.getVolume(Side.BUY, 100));
        }

        @Test
        void fullFillIsFilledWithNothingRemaining() {
            submit(limit(Side.SELL, 100, 10));                       // id 1

            Response response = submit(limit(Side.BUY, 100, 10));   // id 2

            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(0, response.remainingQuantity());
            assertEquals(10, filled(response));
            assertFalse(orderBook.containsOrder(2));
        }

        @Test
        void returnedIdCancelsTheRestingOrder() {
            Response response = submit(limit(Side.BUY, 100, 10));

            CancelResult result = cancel(response.orderId());

            assertEquals(CancelStatus.CANCELLED, result.status());
            assertEquals(10, result.cancelledQuantity());
        }
    }

    @Nested
    class Ioc {

        @Test
        void emptyBookCancelsEverythingAndNothingRests() {
            Response response = submit(ioc(Side.BUY, 100, 10));     // id 1

            assertEquals(1, response.orderId());
            assertTrue(response.trades().isEmpty());
            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertFalse(orderBook.containsOrder(1));
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void partialFillCancelsTheRemainder() {
            submit(limit(Side.SELL, 100, 4));                        // id 1

            Response response = submit(ioc(Side.BUY, 100, 10));     // id 2

            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(6, response.remainingQuantity());
            assertEquals(4, filled(response));                              // 4 filled + 6 cancelled = 10.
            assertFalse(orderBook.containsOrder(2));
            assertEquals(0, orderBook.getNoOfBids());
            assertEquals(0, orderBook.getNoOfAsks());
        }

        @Test
        void fullFillIsFilledNotCancelled() {
            submit(limit(Side.BUY, 100, 10));                        // id 1

            Response response = submit(ioc(Side.SELL, 100, 10));     // id 2

            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(0, response.remainingQuantity());
            assertEquals(10, filled(response));
        }

        @Test
        void fullFillAcrossTwoLevels() {
            submit(limit(Side.SELL, 100, 3));                        // id 1
            submit(limit(Side.SELL, 101, 5));                        // id 2

            Response response = submit(ioc(Side.BUY, 101, 8));      // id 3

            assertEquals(2, response.trades().size());
            assertEquals(100, response.trades().get(0).getPrice());         // Best price first.
            assertEquals(101, response.trades().get(1).getPrice());
            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(0, orderBook.getNoOfAsks());
        }

        @Test
        void stopsAtItsLimitPriceAndCancelsTheRest() {
            submit(limit(Side.SELL, 100, 5));                        // id 1
            submit(limit(Side.SELL, 102, 5));                        // id 2: beyond the IOC's price

            Response response = submit(ioc(Side.BUY, 101, 10));     // id 3

            assertEquals(1, response.trades().size());
            assertEquals(100, response.trades().get(0).getPrice());
            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(5, response.remainingQuantity());
            assertEquals(5, orderBook.getVolume(Side.SELL, 102));           // Untouched.
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void notCrossingCancelsEverythingAndLeavesBookUnchanged() {
            submit(limit(Side.SELL, 105, 5));                        // id 1

            Response response = submit(ioc(Side.BUY, 100, 10));     // id 2

            assertTrue(response.trades().isEmpty());
            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertEquals(5, orderBook.getVolume(Side.SELL, 105));
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void restingOrderPartlyHitByIocStaysOnBook() {
            submit(limit(Side.BUY, 100, 10));                        // id 1

            submit(ioc(Side.SELL, 100, 4));                          // id 2

            assertTrue(orderBook.containsOrder(1));
            assertEquals(6, orderBook.getVolume(Side.BUY, 100));
        }

        @Test
        void cancelledIocCannotTradeWithLaterOrders() {
            submit(ioc(Side.BUY, 100, 10));                          // id 1: cancelled

            Response sell = submit(limit(Side.SELL, 100, 10));       // id 2

            assertTrue(sell.trades().isEmpty());                            // Nothing left to hit.
            assertEquals(OrderStatus.RESTED, sell.orderStatus());
            assertEquals(10, orderBook.getVolume(Side.SELL, 100));
        }

        @Test
        void cancelOfACancelledIocIsUnknown() {
            Response response = submit(ioc(Side.BUY, 100, 10));

            CancelResult result = cancel(response.orderId());

            assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
            assertEquals(0, result.cancelledQuantity());
        }
    }
}
