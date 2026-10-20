import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 2A: cancel through the engine.
- Orders are placed through submit(OrderRequest), so ids come from the engine: 1, 2, 3, ...
- A resting order is cancelled: CANCELLED + its remaining quantity, and it leaves the book.
- Not resting (filled, already cancelled, never existed): UNKNOWN_ORDER + 0, book unchanged.
- A cancel changes what later orders match against, and keeps time priority of the others.
- A cancel never makes an id reusable (D16).

 */
class MatchingEngineCancelTest {

    OrderBook orderBook;
    MatchingEngine engine;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
        engine = new MatchingEngine(orderBook);
    }

    static OrderRequest req(Side side, long price, long quantity) {
        return OrderRequest.limit(side, price, quantity);
    }

    @Test
    void cancelRestingOrderReturnsFullQuantityAndEmptiesBook() {
        engine.submit(req(Side.BUY, 100, 10));                 // id 1 rests

        CancelResult result = engine.cancel(1);

        assertEquals(CancelStatus.CANCELLED, result.status());
        assertEquals(10, result.cancelledQuantity());
        assertFalse(orderBook.containsOrder(1));
        assertEquals(0, orderBook.getNoOfBids());              // Its level went with it.
    }

    @Test
    void cancelPartiallyFilledOrderReturnsOnlyRemaining() {
        engine.submit(req(Side.SELL, 100, 10));                // id 1 rests
        engine.submit(req(Side.BUY, 100, 4));                  // id 2 fills 4 of id 1

        CancelResult result = engine.cancel(1);

        assertEquals(CancelStatus.CANCELLED, result.status());
        assertEquals(6, result.cancelledQuantity());           // 4 filled + 6 cancelled = 10 original.
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void cancelFullyFilledRestingOrderIsUnknown() {
        engine.submit(req(Side.SELL, 100, 10));                // id 1 rests
        engine.submit(req(Side.BUY, 100, 10));                 // id 2 fills it completely

        CancelResult result = engine.cancel(1);

        assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
        assertEquals(0, result.cancelledQuantity());
    }

    @Test
    void cancelIncomingOrderThatNeverRestedIsUnknown() {
        engine.submit(req(Side.SELL, 100, 10));                // id 1 rests
        engine.submit(req(Side.BUY, 100, 10));                 // id 2 fully filled on arrival

        CancelResult result = engine.cancel(2);

        assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
        assertEquals(0, result.cancelledQuantity());
    }

    @Test
    void secondCancelOfSameOrderIsUnknown() {
        engine.submit(req(Side.BUY, 100, 10));                 // id 1 rests
        engine.cancel(1);

        CancelResult second = engine.cancel(1);

        assertEquals(CancelStatus.UNKNOWN_ORDER, second.status());
        assertEquals(0, second.cancelledQuantity());
    }

    @Test
    void cancelOfNeverIssuedIdIsUnknownAndLeavesBookUnchanged() {
        engine.submit(req(Side.BUY, 100, 10));                 // id 1 rests

        CancelResult result = engine.cancel(999);

        assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
        assertEquals(0, result.cancelledQuantity());
        assertTrue(orderBook.containsOrder(1));
        assertEquals(10, orderBook.getVolume(Side.BUY, 100));
        assertEquals(1, orderBook.getNoOfBids());
    }

    @Test
    void cancelledOrderNoLongerMatches() {
        engine.submit(req(Side.SELL, 100, 10));                // id 1: best ask
        engine.submit(req(Side.SELL, 101, 5));                 // id 2
        engine.cancel(1);

        List<Trade> trades = engine.submit(req(Side.BUY, 101, 10)).trades();   // id 3

        assertEquals(1, trades.size());                        // Only id 2 is left to hit.
        assertEquals(2, trades.get(0).getSellOrderId());
        assertEquals(101, trades.get(0).getPrice());
        assertEquals(5, trades.get(0).getQuantity());
        assertTrue(orderBook.containsOrder(3));                // Remainder 5 rests.
        assertEquals(5, orderBook.getVolume(Side.BUY, 101));
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void cancelInMiddleOfLevelKeepsTimePriorityOfOthers() {
        engine.submit(req(Side.SELL, 100, 5));                 // id 1
        engine.submit(req(Side.SELL, 100, 5));                 // id 2
        engine.submit(req(Side.SELL, 100, 5));                 // id 3
        engine.cancel(2);

        List<Trade> trades = engine.submit(req(Side.BUY, 100, 10)).trades();   // id 4

        assertEquals(2, trades.size());
        assertEquals(1, trades.get(0).getSellOrderId());       // Oldest first,
        assertEquals(3, trades.get(1).getSellOrderId());       // then id 3, skipping the cancelled id 2.
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void cancelDoesNotMakeIdReusable() {
        engine.submit(req(Side.BUY, 100, 10));                 // id 1
        engine.cancel(1);
        engine.submit(req(Side.BUY, 100, 10));                 // must be id 2, not id 1 again

        assertEquals(CancelStatus.UNKNOWN_ORDER, engine.cancel(1).status());
        CancelResult result = engine.cancel(2);
        assertEquals(CancelStatus.CANCELLED, result.status());
        assertEquals(10, result.cancelledQuantity());
    }
}
