import org.junit.jupiter.api.*;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineMatchTest {
    OrderBook orderBook;
    MatchingEngine engine;

    @BeforeEach
    void setup() {
        orderBook = new OrderBook();
        engine = new MatchingEngine(orderBook);
    }

    /*

    Assumptions:

    1. Keeping the price of the orders largely the same across all tests.

     */

    // 1. Tests on Trade quantity
    @Test
    void incomingOrderLargerThanRestingOrder() {
        Order restingOrder = new Order(1, Side.SELL, 100, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(40, trade.getQuantity());
        assertEquals(30, incomingOrder.getRemainingQuantity());
        assertEquals(0, restingOrder.getRemainingQuantity());
        assertEquals(100, trade.getPrice());
    }


    @Test
    void incomingOrderSmallerThanRestingOrder() {
        Order restingOrder = new Order(1, Side.SELL, 100, 100, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 30, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(30, trade.getQuantity());
        assertEquals(0, incomingOrder.getRemainingQuantity());
        assertEquals(70, restingOrder.getRemainingQuantity());
    }


    @Test
    void incomingOrderEqualToRestingOrder() {
        Order restingOrder = new Order(1, Side.SELL, 100, 50, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 50, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(50, trade.getQuantity());
        assertEquals(0, incomingOrder.getRemainingQuantity());
        assertEquals(0, restingOrder.getRemainingQuantity());
    }

    // 2. Tests on Trade Price.


    @Test
    void tradeExecutesAtRestingSellPrice() {
        Order restingOrder = new Order(1, Side.SELL, 100, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 150, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(100, trade.getPrice());
        assertNotEquals(150, trade.getPrice());
    }


    @Test
    void tradeExecutesAtRestingBuyPrice() {
        Order restingOrder = new Order(1, Side.BUY, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.SELL, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(120, trade.getPrice());
        assertNotEquals(100, trade.getPrice());
    }

    // 3. Tests on Order IDs.
    @Test
    void incomingBuyGetsBuyOrderId() {
        Order restingOrder = new Order(1, Side.SELL, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(trade.getBuyOrderId(), incomingOrder.getOrderId());
        assertEquals(trade.getSellOrderId(), restingOrder.getOrderId());
    }

    @Test
    void incomingSellGetsSellOrderId() {
        Order restingOrder = new Order(1, Side.BUY, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.SELL, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(trade.getBuyOrderId(), restingOrder.getOrderId());
        assertEquals(trade.getSellOrderId(), incomingOrder.getOrderId());
    }

    // 4. Tests on Incoming Order Updates.
    @Test
    void largerIncomingRemainsPartiallyFilled() {
        Order restingOrder = new Order(1, Side.SELL, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(30, incomingOrder.getRemainingQuantity());
    }

    @Test
    void incomingIsCompletelyFilled() {
        Order restingOrder = new Order(1, Side.SELL, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 100, 40, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(0, incomingOrder.getRemainingQuantity());
    }

    // 5. Tests on Resting Order Updates
    @Test
    void restingIsCompletelyConsumed() {
        Order restingOrder = new Order(1, Side.SELL, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(0, restingOrder.getRemainingQuantity());
    }

    @Test
    void largerRestingRemainsPartiallyFilled() {
        Order restingOrder = new Order(1, Side.SELL, 120, 90, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertEquals(20, restingOrder.getRemainingQuantity());
    }

    // 6. Tests on PriceLevel encapsulation.
    @Test
    void priceLevelIsCompletelyConsumed() {
        Order restingOrder = new Order(1, Side.SELL, 120, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 100, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder);
        PriceLevel restingPL = orderBook.bestLevel(restingOrder.getSide()).get();

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder.getSide()).get());
        assertTrue(restingPL.isFullyConsumed());
    }

    @Test
    void priceLevelWithTwoOrdersIsPartiallyConsumedWithHeadOrderRemoved() {
        Order restingOrder1 = new Order(1, Side.SELL, 100, 40, Instant.now());
        Order restingOrder2 = new Order(2, Side.SELL, 100, 70, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder1);
        orderBook.addOrderToBook(restingOrder2);
        PriceLevel restingPL = orderBook.bestLevel(restingOrder1.getSide()).get();

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder1.getSide()).get());
        assertFalse(restingPL.isFullyConsumed());
        assertEquals(restingPL.peekFirstOrder().getOrderId(), restingOrder2.getOrderId());
        assertEquals(70, restingOrder2.getRemainingQuantity());
    }


    @Test
    void priceLevelWithTwoOrdersIsPartiallyConsumedWithoutHeadOrderRemoved() {
        Order restingOrder1 = new Order(1, Side.SELL, 100, 40, Instant.now());
        Order restingOrder2 = new Order(2, Side.SELL, 100, 70, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 20, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder1);
        orderBook.addOrderToBook(restingOrder2);
        PriceLevel restingPL = orderBook.bestLevel(restingOrder1.getSide()).get();

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder1.getSide()).get());
        assertFalse(restingPL.isFullyConsumed());
        assertEquals(restingPL.peekFirstOrder().getOrderId(), restingOrder1.getOrderId());
        assertEquals(90, restingPL.getTotalVolume());
    }

    @Test
    void volumeOfPriceLevelWith2Orders() {
        Order restingOrder1 = new Order(1, Side.SELL, 100, 30, Instant.now());
        Order restingOrder2 = new Order(2, Side.SELL, 100, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 20, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder1);
        orderBook.addOrderToBook(restingOrder2);
        PriceLevel restingPL = orderBook.bestLevel(restingOrder1.getSide()).get();

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder1.getSide()).get());
        assertEquals(50, restingPL.getTotalVolume());
    }

    // 7. Tests on FIFO ordering of Orders.
    @Test
    void orderingIsConfirmedToBeFIFO() {
        Order restingOrder1 = new Order(1, Side.SELL, 100, 10, Instant.now());
        Order restingOrder2 = new Order(2, Side.SELL, 100, 20, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 15, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder1);
        orderBook.addOrderToBook(restingOrder2);
        PriceLevel restingPL = orderBook.bestLevel(restingOrder1.getSide()).get();

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder1.getSide()).get());
        assertNotEquals(restingPL.peekFirstOrder().getOrderId(), restingOrder1.getOrderId());
        assertEquals(restingPL.peekFirstOrder().getOrderId(), restingOrder2.getOrderId());
        assertEquals(20, restingOrder2.getRemainingQuantity());
    }

    // 8. Tests on TimeStamps

    @Test
    void incomingOrderTimeStampIsLaterThanRestingOrder() throws InterruptedException {
        Order restingOrder1 = new Order(1, Side.SELL, 100, 40, Instant.now());
        Thread.sleep(100);
        Order incomingOrder = new Order(2, Side.BUY, 105, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder1);

        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder1.getSide()).get());
        assertTrue(incomingOrder.getTimestamp().isAfter(restingOrder1.getTimestamp()));
    }

    @Test
    void timeStampsOfOrdersIsEarlierThanTrade() throws  InterruptedException{
        Order restingOrder1 = new Order(1, Side.SELL, 100, 40, Instant.now());
        Order incomingOrder = new Order(2, Side.BUY, 105, 70, Instant.now());

        // Insert the restingOrder into the OrderBook.
        orderBook.addOrderToBook(restingOrder1);

        Thread.sleep(100);
        Trade trade = engine.match(incomingOrder, orderBook.bestLevel(restingOrder1.getSide()).get());
        assertTrue(trade.getTimestamp().isAfter(restingOrder1.getTimestamp()));
        assertTrue(trade.getTimestamp().isAfter(incomingOrder.getTimestamp()));
    }
}