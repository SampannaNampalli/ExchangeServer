import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 2B2: FOK (fill-or-kill).
- Whole quantity fills now -> FILLED, 0 remaining, trades in normal price-time priority.
- Otherwise it is killed  -> CANCELLED, full quantity remaining, NO trades, book exactly as before.
- Killed means checked before matching, never matched-then-undone: resting orders keep their time priority.
- A killed FOK never rests.

 */
class MatchingEngineFokTest {

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

    static OrderRequest fok(Side side, long price, long quantity) {
        return new OrderRequest(side, price, quantity, OrderType.FOK);
    }

    @Nested
    class Filled {

        @Test
        void exactQuantityAtOneLevel() {
            submit(limit(Side.SELL, 100, 10));                       // id 1

            Response response = submit(fok(Side.BUY, 100, 10));      // id 2

            assertEquals(2, response.orderId());
            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(0, response.remainingQuantity());
            assertEquals(1, response.trades().size());
            assertEquals(0, orderBook.getNoOfAsks());
        }

        @Test
        void acrossTwoLevelsBestPriceFirst() {
            submit(limit(Side.SELL, 100, 4));                        // id 1
            submit(limit(Side.SELL, 101, 6));                        // id 2

            Response response = submit(fok(Side.BUY, 101, 10));      // id 3

            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(2, response.trades().size());
            assertEquals(100, response.trades().get(0).getPrice());
            assertEquals(101, response.trades().get(1).getPrice());
            assertEquals(0, orderBook.getNoOfAsks());
        }

        @Test
        void acrossTwoOrdersInOneLevelOldestFirst() {
            submit(limit(Side.SELL, 100, 4));                        // id 1
            submit(limit(Side.SELL, 100, 6));                        // id 2

            Response response = submit(fok(Side.BUY, 100, 10));      // id 3

            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(1, response.trades().get(0).getSellOrderId());
            assertEquals(2, response.trades().get(1).getSellOrderId());
        }

        @Test
        void leavesTheRestOfTheLastLevelResting() {
            submit(limit(Side.SELL, 100, 4));                        // id 1
            submit(limit(Side.SELL, 101, 10));                       // id 2

            Response response = submit(fok(Side.BUY, 101, 10));      // id 3

            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertTrue(orderBook.containsOrder(2));
            assertEquals(4, orderBook.getVolume(Side.SELL, 101));           // 10 - 6 taken.
        }

        @Test
        void sellSideWalksBidsFromHighestDown() {
            submit(limit(Side.BUY, 100, 4));                         // id 1
            submit(limit(Side.BUY, 99, 6));                          // id 2

            Response response = submit(fok(Side.SELL, 99, 10));     // id 3

            assertEquals(OrderStatus.FILLED, response.orderStatus());
            assertEquals(100, response.trades().get(0).getPrice());
            assertEquals(99, response.trades().get(1).getPrice());
            assertEquals(0, orderBook.getNoOfBids());
        }
    }

    @Nested
    class Killed {

        @Test
        void notEnoughVolumeLeavesBookUntouched() {
            submit(limit(Side.SELL, 100, 4));                        // id 1

            Response response = submit(fok(Side.BUY, 100, 10));      // id 2

            assertEquals(2, response.orderId());
            assertTrue(response.trades().isEmpty());                        // Not even the 4 that crossed.
            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertTrue(orderBook.containsOrder(1));
            assertEquals(4, orderBook.getVolume(Side.SELL, 100));
            assertFalse(orderBook.containsOrder(2));
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void oneUnitShortIsKilled() {
            submit(limit(Side.SELL, 100, 9));                        // id 1

            Response response = submit(fok(Side.BUY, 100, 10));      // id 2

            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(9, orderBook.getVolume(Side.SELL, 100));
        }

        @Test
        void volumeBeyondItsPriceDoesNotCount() {
            submit(limit(Side.SELL, 100, 4));                        // id 1
            submit(limit(Side.SELL, 102, 6));                        // id 2: enough in total, but above 101

            Response response = submit(fok(Side.BUY, 101, 10));      // id 3

            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertTrue(response.trades().isEmpty());
            assertEquals(4, orderBook.getVolume(Side.SELL, 100));
            assertEquals(6, orderBook.getVolume(Side.SELL, 102));
        }

        @Test
        void sellSideVolumeBelowItsPriceDoesNotCount() {
            submit(limit(Side.BUY, 100, 4));                         // id 1
            submit(limit(Side.BUY, 99, 6));                          // id 2: below the FOK's 100

            Response response = submit(fok(Side.SELL, 100, 10));     // id 3

            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(4, orderBook.getVolume(Side.BUY, 100));
            assertEquals(6, orderBook.getVolume(Side.BUY, 99));
        }

        @Test
        void emptyBook() {
            Response response = submit(fok(Side.BUY, 100, 10));      // id 1

            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(10, response.remainingQuantity());
            assertEquals(0, orderBook.getNoOfBids());
        }

        @Test
        void nothingCrosses() {
            submit(limit(Side.SELL, 105, 10));                       // id 1

            Response response = submit(fok(Side.BUY, 100, 10));      // id 2

            assertEquals(OrderStatus.CANCELLED, response.orderStatus());
            assertEquals(10, orderBook.getVolume(Side.SELL, 105));
        }

        @Test
        void restingOrdersKeepTheirTimePriority() {
            submit(limit(Side.SELL, 100, 3));                        // id 1: first in line
            submit(limit(Side.SELL, 100, 3));                        // id 2
            submit(fok(Side.BUY, 100, 10));                          // id 3: killed

            Response response = submit(limit(Side.BUY, 100, 3));     // id 4

            assertEquals(1, response.trades().get(0).getSellOrderId());     // id 1 is still first.
        }

        @Test
        void killedFokCannotTradeWithLaterOrders() {
            submit(fok(Side.BUY, 100, 10));                          // id 1: killed

            Response sell = submit(limit(Side.SELL, 100, 10));       // id 2

            assertTrue(sell.trades().isEmpty());
            assertEquals(OrderStatus.RESTED, sell.orderStatus());
        }

        @Test
        void cancelOfAKilledFokIsUnknown() {
            Response response = submit(fok(Side.BUY, 100, 10));

            CancelResult result = cancel(response.orderId());

            assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
            assertEquals(0, result.cancelledQuantity());
        }
    }
}
