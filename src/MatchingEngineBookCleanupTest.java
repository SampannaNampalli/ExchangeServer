import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 4: after matching, the book must be clean.
- A fully filled resting order leaves the id map, and its level goes if it was the last order.
- A partially filled resting order stays, with reduced volume.
- An incoming order that never rests never enters the map.
- An incoming remainder that rests is in the map, so it can be cancelled.

 */
class MatchingEngineBookCleanupTest {

    OrderBook orderBook;
    MatchingEngine engine;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
        engine = new MatchingEngine(orderBook);
    }

    static Order order(long id, Side side, long price, long quantity) {
        return new Order(id, side, price, quantity, Instant.EPOCH);
    }

    @Test
    void exactMatchRemovesRestingOrderAndItsLevel() {
        orderBook.addOrderToBook(order(1, Side.SELL, 100, 10));

        List<Trade> trades = engine.submit(order(2, Side.BUY, 100, 10));

        assertEquals(1, trades.size());
        assertFalse(orderBook.containsOrder(1));
        assertFalse(orderBook.containsOrder(2));   // Fully filled on arrival: never rested.
        assertEquals(0, orderBook.getNoOfAsks());
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void partiallyFilledRestingOrderStays() {
        orderBook.addOrderToBook(order(1, Side.SELL, 100, 10));

        engine.submit(order(2, Side.BUY, 100, 4));

        assertTrue(orderBook.containsOrder(1));
        assertEquals(6, orderBook.getVolume(Side.SELL, 100));
        assertEquals(1, orderBook.getNoOfAsks());
        assertFalse(orderBook.containsOrder(2));
    }

    @Test
    void filledHeadIsRemovedAndNextOrderInLevelBecomesHead() {
        orderBook.addOrderToBook(order(1, Side.SELL, 100, 5));
        orderBook.addOrderToBook(order(2, Side.SELL, 100, 5));

        engine.submit(order(3, Side.BUY, 100, 5));

        assertFalse(orderBook.containsOrder(1));
        assertTrue(orderBook.containsOrder(2));
        assertEquals(2, orderBook.bestAsk().get().peekFirstOrder().getOrderId());
        assertEquals(5, orderBook.getVolume(Side.SELL, 100));
    }

    @Test
    void incomingRemainderRestsAndCanBeCancelled() {
        orderBook.addOrderToBook(order(1, Side.SELL, 100, 10));

        engine.submit(order(2, Side.BUY, 100, 15));

        assertFalse(orderBook.containsOrder(1));
        assertTrue(orderBook.containsOrder(2));
        assertEquals(5, orderBook.getVolume(Side.BUY, 100));

        CancelResult result = orderBook.cancel(2);
        assertEquals(CancelStatus.CANCELLED, result.status());
        assertEquals(5, result.cancelledQuantity());
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void sweepRemovesEveryDrainedLevelAndStopsAtLimit() {
        orderBook.addOrderToBook(order(1, Side.SELL, 100, 5));
        orderBook.addOrderToBook(order(2, Side.SELL, 101, 5));
        orderBook.addOrderToBook(order(3, Side.SELL, 102, 5));

        // Limit 101: drains 100 and 101, does not cross 102, rests the remaining 2 as a bid at 101.
        List<Trade> trades = engine.submit(order(4, Side.BUY, 101, 12));

        assertEquals(2, trades.size());
        assertEquals(100, trades.get(0).getPrice());
        assertEquals(101, trades.get(1).getPrice());

        assertFalse(orderBook.containsOrder(1));
        assertFalse(orderBook.containsOrder(2));
        assertTrue(orderBook.containsOrder(3));
        assertTrue(orderBook.containsOrder(4));

        assertEquals(1, orderBook.getNoOfAsks());
        assertEquals(102, orderBook.bestAsk().get().getPrice());
        assertEquals(2, orderBook.getVolume(Side.BUY, 101));
    }

    @Test
    void cancelledOrderIsSkippedByLaterMatch() {
        orderBook.addOrderToBook(order(1, Side.SELL, 100, 5));
        orderBook.addOrderToBook(order(2, Side.SELL, 100, 5));
        orderBook.cancel(1);

        List<Trade> trades = engine.submit(order(3, Side.BUY, 100, 5));

        assertEquals(1, trades.size());
        assertEquals(2, trades.get(0).getSellOrderId());
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void sellSideSweepCleansBidSide() {
        // Mirror of the buy-side tests, so both TreeMaps are exercised through the engine.
        orderBook.addOrderToBook(order(1, Side.BUY, 100, 5));
        orderBook.addOrderToBook(order(2, Side.BUY, 99, 5));

        List<Trade> trades = engine.submit(order(3, Side.SELL, 99, 10));

        assertEquals(2, trades.size());
        assertEquals(1, trades.get(0).getBuyOrderId());
        assertEquals(2, trades.get(1).getBuyOrderId());
        assertEquals(0, orderBook.getNoOfBids());
        assertFalse(orderBook.containsOrder(1));
        assertFalse(orderBook.containsOrder(2));
        assertFalse(orderBook.containsOrder(3));
    }
}
