import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 3A: commands as data.
- A new-order command carries the request plus its stamps: sequence number, timestamp, order id.
- A cancel command carries the target order id plus its sequence number and timestamp.
- The engine processes a command using only the command and the book: no clock, no id counter.
- Every trade a command causes carries that command's timestamp.

Ids and timestamps below are deliberately far from what the engine's own counter (1, 2, ...)
and the wall clock would produce, so a test fails if either leaks into the command path.

 */
class MatchingEngineCommandTest {

    static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-01-01T10:00:05Z");
    static final Instant T3 = Instant.parse("2026-01-01T10:00:09Z");

    OrderBook orderBook;
    MatchingEngine engine;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
        engine = new MatchingEngine(orderBook);
    }

    static NewOrderCommand newOrder(long seq, Instant ts, long orderId, OrderRequest request) {
        return new NewOrderCommand(request, seq, ts, orderId);
    }

    static CancelOrderCommand cancelOrder(long seq, Instant ts, long orderId) {
        return new CancelOrderCommand(seq, ts, orderId);
    }

    // ---------- New-order command: stamped id ----------

    @Test
    void responseCarriesTheStampedOrderId() {
        Response response = engine.submitNewOrderCommand(
                newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));

        assertEquals(100, response.orderId());                              // Not the engine's counter (1).
        assertEquals(OrderStatus.RESTED, response.orderStatus());
        assertEquals(10, response.remainingQuantity());
    }

    @Test
    void stampedIdsWithGapsAreUsedAsGiven() {
        Response first = engine.submitNewOrderCommand(
                newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));
        Response second = engine.submitNewOrderCommand(
                newOrder(2, T2, 205, OrderRequest.limit(Side.BUY, 99, 10)));   // Gap allowed (D16).

        assertEquals(100, first.orderId());
        assertEquals(205, second.orderId());
        assertTrue(orderBook.containsOrder(100));
        assertTrue(orderBook.containsOrder(205));
        assertFalse(orderBook.containsOrder(1));                            // Counter values never appear.
        assertFalse(orderBook.containsOrder(2));
    }

    @Test
    void tradeCarriesStampedIdsOnBothSides() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));

        Response response = engine.submitNewOrderCommand(
                newOrder(2, T2, 205, OrderRequest.limit(Side.BUY, 100, 10)));

        Trade trade = response.trades().get(0);
        assertEquals(205, trade.getBuyOrderId());
        assertEquals(100, trade.getSellOrderId());
    }

    // ---------- New-order command: stamped timestamp ----------

    @Test
    void restingOrderCarriesTheCommandTimestamp() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));

        Order resting = orderBook.bestBid().get().peekFirstOrder();
        assertEquals(T1, resting.getTimestamp());
    }

    @Test
    void tradeCarriesTheIncomingCommandTimestampNotTheRestingOne() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));

        Response response = engine.submitNewOrderCommand(
                newOrder(2, T2, 205, OrderRequest.limit(Side.BUY, 100, 4)));

        assertEquals(1, response.trades().size());
        assertEquals(T2, response.trades().get(0).getTimestamp());          // Not T1 (resting), not now.
    }

    @Test
    void everyTradeFromOneCommandSharesItsTimestamp() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 5)));
        engine.submitNewOrderCommand(newOrder(2, T2, 101, OrderRequest.limit(Side.SELL, 101, 5)));

        Response response = engine.submitNewOrderCommand(
                newOrder(3, T3, 102, OrderRequest.limit(Side.BUY, 101, 10)));

        assertEquals(2, response.trades().size());
        for (Trade trade : response.trades()) {
            assertEquals(T3, trade.getTimestamp());
        }
    }

    // ---------- New-order command: every type still works ----------

    @Test
    void marketOrderCommandMatches() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));

        Response response = engine.submitNewOrderCommand(
                newOrder(2, T2, 205, OrderRequest.market(Side.BUY, 10)));      // Review #1: used to throw.

        assertEquals(205, response.orderId());
        assertEquals(OrderStatus.FILLED, response.orderStatus());
        assertEquals(T2, response.trades().get(0).getTimestamp());
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void otherOrderTypesGoThroughTheCommandPath() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));

        Response ioc = engine.submitNewOrderCommand(
                newOrder(2, T2, 200, new OrderRequest(Side.BUY, 100, 4, OrderType.IOC)));
        Response fok = engine.submitNewOrderCommand(
                newOrder(3, T2, 300, new OrderRequest(Side.BUY, 100, 50, OrderType.FOK)));
        Response postOnly = engine.submitNewOrderCommand(
                newOrder(4, T3, 400, new OrderRequest(Side.BUY, 100, 5, OrderType.POST_ONLY)));

        assertEquals(new Response(200, ioc.trades(), OrderStatus.FILLED, 0), ioc);
        assertEquals(new Response(300, List.of(), OrderStatus.CANCELLED, 50), fok);
        assertEquals(new Response(400, List.of(), OrderStatus.REJECTED, 5), postOnly);
        assertEquals(6, orderBook.getVolume(Side.SELL, 100));
    }

    // ---------- Cancel command ----------

    @Test
    void cancelCommandCancelsByStampedId() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));

        CancelResult result = engine.submitCancelOrderCommand(cancelOrder(2, T2, 100));

        assertEquals(new CancelResult(CancelStatus.CANCELLED, 10), result);
        assertFalse(orderBook.containsOrder(100));
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void cancelCommandReportsWhatWasLeftAfterAPartialFill() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));
        engine.submitNewOrderCommand(newOrder(2, T2, 205, OrderRequest.limit(Side.SELL, 100, 4)));

        CancelResult result = engine.submitCancelOrderCommand(cancelOrder(3, T3, 100));

        assertEquals(new CancelResult(CancelStatus.CANCELLED, 6), result);
    }

    @Test
    void cancelCommandUsesTheOrderIdNotTheSequenceNumber() {
        engine.submitNewOrderCommand(newOrder(1, T1, 7, OrderRequest.limit(Side.BUY, 99, 3)));
        engine.submitNewOrderCommand(newOrder(2, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));

        CancelResult result = engine.submitCancelOrderCommand(cancelOrder(7, T2, 100));   // seq 7, target 100

        assertEquals(new CancelResult(CancelStatus.CANCELLED, 10), result);
        assertTrue(orderBook.containsOrder(7));                             // The order whose id equals the seq is untouched.
    }

    @Test
    void cancelCommandForAnIdThatNeverRestedIsUnknown() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));
        engine.submitNewOrderCommand(newOrder(2, T2, 205, OrderRequest.limit(Side.BUY, 100, 10)));   // Fills, never rests.

        assertEquals(new CancelResult(CancelStatus.UNKNOWN_ORDER, 0),
                engine.submitCancelOrderCommand(cancelOrder(3, T3, 205)));
        assertEquals(new CancelResult(CancelStatus.UNKNOWN_ORDER, 0),
                engine.submitCancelOrderCommand(cancelOrder(4, T3, 999)));
    }

    // ---------- Order ids must strictly increase (D16) ----------

    @Test
    void repeatedOrderIdIsRejectedBeforeTheBookChanges() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));

        // Would trade 4 against order 100 if it got that far.
        assertThrows(IllegalStateException.class, () -> engine.submitNewOrderCommand(
                newOrder(2, T2, 100, OrderRequest.limit(Side.BUY, 100, 4))));

        assertEquals(10, orderBook.getVolume(Side.SELL, 100));               // Nothing matched.
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void lowerOrderIdIsRejected() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 10)));

        assertThrows(IllegalStateException.class, () -> engine.submitNewOrderCommand(
                newOrder(2, T2, 99, OrderRequest.limit(Side.BUY, 90, 4))));

        assertEquals(0, orderBook.getNoOfBids());                           // Did not rest either.
    }

    @Test
    void reuseOfAnIdThatFilledAndLeftTheBookIsStillRejected() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 100, 4)));
        engine.submitNewOrderCommand(newOrder(2, T1, 101, OrderRequest.limit(Side.BUY, 100, 4)));   // Fills, never rests.

        assertThrows(IllegalStateException.class, () -> engine.submitNewOrderCommand(
                newOrder(3, T2, 101, OrderRequest.limit(Side.SELL, 105, 10))));

        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void aRejectedIdDoesNotBlockTheNextHigherOne() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));
        assertThrows(IllegalStateException.class, () -> engine.submitNewOrderCommand(
                newOrder(2, T2, 100, OrderRequest.limit(Side.BUY, 99, 10))));

        Response response = engine.submitNewOrderCommand(newOrder(3, T2, 101, OrderRequest.limit(Side.BUY, 99, 10)));

        assertEquals(OrderStatus.RESTED, response.orderStatus());
    }

    @Test
    void cancelOfAnOlderIdIsNotAffectedByTheIdCheck() {
        engine.submitNewOrderCommand(newOrder(1, T1, 100, OrderRequest.limit(Side.BUY, 100, 10)));
        engine.submitNewOrderCommand(newOrder(2, T1, 205, OrderRequest.limit(Side.BUY, 99, 10)));

        // The check is on new order ids only; a cancel targets an existing (lower) id.
        assertEquals(new CancelResult(CancelStatus.CANCELLED, 10),
                engine.submitCancelOrderCommand(cancelOrder(3, T2, 100)));
    }

    // ---------- Determinism: same commands, same results ----------

    @Test
    void sameCommandsIntoTwoFreshEnginesGiveIdenticalOutputsAndBooks() throws InterruptedException {
        List<Object> commands = List.of(
                newOrder(1, T1, 100, OrderRequest.limit(Side.SELL, 101, 5)),
                newOrder(2, T1, 101, OrderRequest.limit(Side.SELL, 100, 5)),
                newOrder(3, T2, 102, OrderRequest.limit(Side.BUY, 99, 8)),
                newOrder(4, T2, 103, OrderRequest.limit(Side.BUY, 101, 7)),      // Two trades.
                cancelOrder(5, T3, 102),
                newOrder(6, T3, 104, OrderRequest.market(Side.SELL, 2)));        // Bids now empty: cancelled.

        List<String> live = run(commands);
        Thread.sleep(20);                                                   // Any clock read would now differ.
        List<String> replay = run(commands);                                // Same command objects, fresh engine.

        assertEquals(live, replay);
    }

    // Feeds the commands to a fresh engine and renders every output plus the final book as text.
    private static List<String> run(List<Object> commands) {
        OrderBook book = new OrderBook();
        MatchingEngine e = new MatchingEngine(book);
        List<String> out = new ArrayList<>();

        for (Object command : commands) {
            if (command instanceof NewOrderCommand n) {
                Response r = e.submitNewOrderCommand(n);
                out.add("R " + r.orderId() + " " + r.orderStatus() + " " + r.remainingQuantity());
                for (Trade t : r.trades()) {
                    out.add("T " + t.getQuantity() + "@" + t.getPrice() + " b" + t.getBuyOrderId()
                            + " s" + t.getSellOrderId() + " " + t.getTimestamp());
                }
            } else {
                out.add("C " + e.submitCancelOrderCommand((CancelOrderCommand) command));
            }
        }
        book.askPriceLevelIterator().forEachRemaining(l -> out.add("ASK " + l.getPrice() + " " + l.getTotalVolume()));
        book.bidPriceLevelIterator().forEachRemaining(l -> out.add("BID " + l.getPrice() + " " + l.getTotalVolume()));
        return out;
    }
}
