import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 2B3: market orders.
- No price: built with OrderRequest.market(side, quantity).
- Takes whatever is on the other side, best price first, at any price, until filled or the side runs out.
- Nothing left -> FILLED, 0. Leftover -> CANCELLED with the leftover; it never rests.

 */
class MatchingEngineMarketTest {

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

    static OrderRequest market(Side side, long quantity) {
        return OrderRequest.market(side, quantity);
    }

    static long filled(Response response) {
        return response.trades().stream().mapToLong(Trade::getQuantity).sum();
    }

    @Test
    void exactFillAtOneLevel() {
        engine.submit(limit(Side.SELL, 100, 10));                           // id 1

        Response response = engine.submit(market(Side.BUY, 10));            // id 2

        assertEquals(2, response.orderId());
        assertEquals(OrderStatus.FILLED, response.orderStatus());
        assertEquals(0, response.remainingQuantity());
        assertEquals(100, response.trades().get(0).getPrice());
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void walksLevelsAtAnyPriceBestFirst() {
        engine.submit(limit(Side.SELL, 100, 5));                            // id 1
        engine.submit(limit(Side.SELL, 5000, 5));                           // id 2: far away, still taken

        Response response = engine.submit(market(Side.BUY, 7));             // id 3

        assertEquals(OrderStatus.FILLED, response.orderStatus());
        assertEquals(2, response.trades().size());
        assertEquals(100, response.trades().get(0).getPrice());
        assertEquals(5, response.trades().get(0).getQuantity());
        assertEquals(5000, response.trades().get(1).getPrice());
        assertEquals(2, response.trades().get(1).getQuantity());
        assertEquals(3, orderBook.getVolume(Side.SELL, 5000));
    }

    @Test
    void sellWalksBidsFromHighestDown() {
        engine.submit(limit(Side.BUY, 100, 4));                             // id 1
        engine.submit(limit(Side.BUY, 1, 6));                               // id 2: lowest possible price

        Response response = engine.submit(market(Side.SELL, 10));           // id 3

        assertEquals(OrderStatus.FILLED, response.orderStatus());
        assertEquals(100, response.trades().get(0).getPrice());
        assertEquals(1, response.trades().get(1).getPrice());
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void oldestFirstWithinALevel() {
        engine.submit(limit(Side.SELL, 100, 3));                            // id 1
        engine.submit(limit(Side.SELL, 100, 3));                            // id 2

        Response response = engine.submit(market(Side.BUY, 4));             // id 3

        assertEquals(1, response.trades().get(0).getSellOrderId());
        assertEquals(3, response.trades().get(0).getQuantity());
        assertEquals(2, response.trades().get(1).getSellOrderId());
        assertEquals(1, response.trades().get(1).getQuantity());
        assertEquals(2, orderBook.getVolume(Side.SELL, 100));
    }

    @Test
    void sideRunsOutLeftoverIsCancelled() {
        engine.submit(limit(Side.SELL, 100, 4));                            // id 1

        Response response = engine.submit(market(Side.BUY, 10));            // id 2

        assertEquals(OrderStatus.CANCELLED, response.orderStatus());
        assertEquals(6, response.remainingQuantity());
        assertEquals(4, filled(response));                                  // 4 filled + 6 cancelled = 10.
        assertFalse(orderBook.containsOrder(2));
        assertEquals(0, orderBook.getNoOfBids());
        assertEquals(0, orderBook.getNoOfAsks());
    }

    @Test
    void emptyBookCancelsEverything() {
        Response response = engine.submit(market(Side.BUY, 10));            // id 1

        assertTrue(response.trades().isEmpty());
        assertEquals(OrderStatus.CANCELLED, response.orderStatus());
        assertEquals(10, response.remainingQuantity());
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void ownSideIsNeverTouched() {
        engine.submit(limit(Side.BUY, 100, 10));                            // id 1: same side as the market order

        Response response = engine.submit(market(Side.BUY, 10));            // id 2

        assertTrue(response.trades().isEmpty());
        assertEquals(OrderStatus.CANCELLED, response.orderStatus());
        assertEquals(10, orderBook.getVolume(Side.BUY, 100));
        assertFalse(orderBook.containsOrder(2));
    }

    @Test
    void cancelledMarketOrderCannotTradeWithLaterOrders() {
        engine.submit(market(Side.BUY, 10));                                // id 1: cancelled

        Response sell = engine.submit(limit(Side.SELL, 100, 10));           // id 2

        assertTrue(sell.trades().isEmpty());
        assertEquals(OrderStatus.RESTED, sell.orderStatus());
    }

    @Test
    void cancelOfAMarketOrderIsUnknown() {
        Response response = engine.submit(market(Side.BUY, 10));

        CancelResult result = engine.cancel(response.orderId());

        assertEquals(CancelStatus.UNKNOWN_ORDER, result.status());
        assertEquals(0, result.cancelledQuantity());
    }
}
