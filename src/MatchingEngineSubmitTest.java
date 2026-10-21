import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineSubmitTest {

    OrderBook orderBook;
    MatchingEngine engine;
    Sequencer sequencer = new Sequencer();

    // Step 3B: requests reach the engine only as stamped commands. Ids start at 1, as before.
    Response submit(OrderRequest request) {
        return engine.submitNewOrderCommand(sequencer.createStampedNewOrderCommand(request));
    }

    // Builds the Order the engine would build from the next stamped command, for tests that inspect it.
    Order createOrder(OrderRequest request) {
        NewOrderCommand command = sequencer.createStampedNewOrderCommand(request);
        return engine.createOrder(command.getOrderId(), command.getOrderRequest(), command.getTimeStamp());
    }

    @BeforeEach
    void setup() {
        orderBook = new OrderBook();
        engine = new MatchingEngine(orderBook);
    }

    /*

    I have changed some things in the project:

    1. Created a new class OrderRequest(Side side, long price, long quantity) which will contain the information
    of the order to be submitted.
    2. Made the createOrder(OrderRequest orderRequest) private.
    3. Changed the name of the submit(Order incomingOrder) method to process(Order incomingOrder), since it
    suits it better.
    4. To submit an order for trade, the submit(OrderRequest orderRequest) method must be called, which internally
    creates a proper Order object, submits it for processing and returns the list of trades processed.
    5. The API that will now be exposed will be submit(OrderRequest orderRequest).
    6. Added the noOfOrders field to the PriceLevel class that returns the currently valid no. of orders
    in a price-level.

    Changes made only for testing purposes:

    1. Made the createOrder(OrderRequest orderRequest) method public.
    2. Created a submit(Order incomingOrder) method

    Method of creating and submitting orders.

    1. Take OrderRequest objects as inputs (from the client side itself).
    2. Create an Order object of the incoming order from its OrderRequest object for testing purposes.
    3. Submit the OrderRequest object of the resting order(s) to bypass order creation.
    4. Submit the already created Order object of the incoming order for matching.

     */

    @Test
    void incomingBuyOnEmptyBook() {
        OrderRequest orderRequest = OrderRequest.limit(Side.BUY, 100, 50);

        List<Trade> trades = submit(orderRequest).trades();

        assertTrue(trades.isEmpty());
        assertEquals(1, orderBook.bestBid().get().getNoOfOrders());
        assertEquals(50, orderBook.bestBid().get().getTotalVolume());
        assertTrue(orderBook.bestAsk().isEmpty());
    }

    @Test
    void incomingBuyExactlyMatchesSingleRestingAsk() {

        OrderRequest restingOrderRequest = OrderRequest.limit(Side.SELL, 100, 40);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 100, 40);

        submit(restingOrderRequest);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // Exactly one trade is executed.
        assertEquals(1, trades.size());

        // The trade quantity is 40.
        Trade trade = trades.get(0);
        assertEquals(40, trade.getQuantity());

        // The incoming order has been consumed.
        assertEquals(0, incomingOrder.getRemainingQuantity());

        // The book is empty/the sell price level has been removed.
        assertTrue(orderBook.bestAsk().isEmpty());

        // The bid side is also empty.
        assertTrue(orderBook.bestBid().isEmpty());
    }

    @Test
    void ordersDoNotCross() {

        // Create order request objects.
        OrderRequest restingOrderRequest1 = OrderRequest.limit(Side.SELL, 100, 20);
        OrderRequest restingOrderRequest2 = OrderRequest.limit(Side.SELL, 102, 40);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 85, 100);

        // Submit the orders that need to be rested.
        submit(restingOrderRequest1);
        submit(restingOrderRequest2);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // Size of trades.
        assertEquals(0, trades.size());

        // Remaining in the book.
        assertFalse(orderBook.bestAsk().isEmpty());
        assertEquals(20, orderBook.bestAsk().get().getTotalVolume());
        assertEquals(2, orderBook.getNoOfAsks());

        // Incoming order - quantity and addition to the order book.
        assertEquals(100, incomingOrder.getRemainingQuantity());
        assertFalse(orderBook.bestBid().isEmpty());
    }

    @Test
    void stopWhenPricesNoLongerCrossWith2RestingSells() {

        OrderRequest restingOrderRequest1 = OrderRequest.limit(Side.SELL, 100, 20);
        OrderRequest restingOrderRequest2 = OrderRequest.limit(Side.SELL, 110, 40);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 105, 40);

        submit(restingOrderRequest1);
        submit(restingOrderRequest2);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // Size of trades.
        assertEquals(1, trades.size());

        // Trade 1 quantity and price.
        assertEquals(20, trades.get(0).getQuantity());
        assertEquals(100, trades.get(0).getPrice());

        // Remaining in the book.
        assertFalse(orderBook.bestAsk().isEmpty());
        assertEquals(110, orderBook.bestAsk().get().getPrice());
        assertEquals(40, orderBook.bestAsk().get().getTotalVolume());

        // Incoming order - quantity and addition to the order book.
        assertEquals(20, incomingOrder.getRemainingQuantity());
        assertFalse(orderBook.bestBid().isEmpty());
    }

    @Test
    void incomingBuyLargerThanRestingAsk_restsRemainingQuantity() {
        OrderRequest restingOrderRequest = OrderRequest.limit(Side.SELL, 100, 40);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 105, 70);

        submit(restingOrderRequest);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // Exactly one trade is executed.
        assertEquals(1, trades.size());

        // The trade quantity is 40.
        Trade trade = trades.get(0);
        assertEquals(40, trade.getQuantity());

        // The incoming order has 30 remaining quantity.
        assertEquals(30, incomingOrder.getRemainingQuantity());

        // The incoming order has been rested on the order book.
        assertEquals(1, orderBook.bestBid().get().getNoOfOrders());

        // The best bid is at 105.
        assertEquals(105, orderBook.bestBid().get().getPrice());

        // The book is empty/the sell price level has been removed.
        assertTrue(orderBook.bestAsk().isEmpty());
    }

    @Test
    void incomingSmallerBuyMatchesDoubleRestingAsk() {
        OrderRequest restingOrderRequest = OrderRequest.limit(Side.SELL, 100, 70);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 105, 40);

        Order restingOrder = createOrder(restingOrderRequest);

        engine.submit(restingOrder);

        // Directly submitting the incoming order for this test since no direct checking is performed.
        List<Trade> trades = submit(incomingOrderRequest).trades();

        // Exactly one trade is executed.
        assertEquals(1, trades.size());

        // The resting order has 30 remaining quantity.
        assertEquals(30, restingOrder.getRemainingQuantity());

        // The incoming order has not been rested on the order book.
        assertTrue(orderBook.bestBid().isEmpty());

        // The same ask price remains.
        assertEquals(restingOrder.getPrice(), orderBook.bestAsk().get().getPrice());
    }

    @Test
    void multiLevelWalkWith3RestingSellsAnd1Buy() {

        // 3 resting sells.
        OrderRequest restingOrderRequest1 = OrderRequest.limit(Side.SELL, 100, 20);
        OrderRequest restingOrderRequest2 = OrderRequest.limit(Side.SELL, 101, 30);
        OrderRequest restingOrderRequest3 = OrderRequest.limit(Side.SELL, 102, 40);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 105, 60);

        submit(restingOrderRequest1);
        submit(restingOrderRequest2);
        submit(restingOrderRequest3);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // IDs of orders.
        assertEquals(1, trades.get(0).getSellOrderId());
        assertEquals(2, trades.get(1).getSellOrderId());
        assertEquals(3, trades.get(2).getSellOrderId());

        // Size of trades.
        assertEquals(3, trades.size());

        // Trade 1 quantity and price.
        assertEquals(20, trades.get(0).getQuantity());
        assertEquals(100, trades.get(0).getPrice());

        // Trade 2 quantity and price.
        assertEquals(30, trades.get(1).getQuantity());
        assertEquals(101, trades.get(1).getPrice());

        // Trade 3 quantity and price.
        assertEquals(10, trades.get(2).getQuantity());
        assertEquals(102, trades.get(2).getPrice());

        // Remaining in the book.
        assertFalse(orderBook.bestAsk().isEmpty());
        assertEquals(102, orderBook.bestAsk().get().getPrice());
        assertEquals(30, orderBook.bestAsk().get().getTotalVolume());

        // Incoming order.
        assertEquals(0, incomingOrder.getRemainingQuantity());
    }

    @Test
    void restingSideBecomesEmpty() {
        OrderRequest restingOrderRequest1 = OrderRequest.limit(Side.SELL, 100, 20);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 100, 100);

        submit(restingOrderRequest1);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // Size of trades.
        assertEquals(1, trades.size());

        // Trade 1 quantity and price.
        assertEquals(20, trades.get(0).getQuantity());
        assertEquals(100, trades.get(0).getPrice());

        // Remaining in the book - nothing.
        assertTrue(orderBook.bestAsk().isEmpty());

        // Incoming order - quantity and addition to the order book.
        assertEquals(80, incomingOrder.getRemainingQuantity());
        assertFalse(orderBook.bestBid().isEmpty());
    }

    @Test
    void checkingFIFOAcrossMultipleCalls() {
        OrderRequest restingOrderRequest1 = OrderRequest.limit(Side.SELL, 100, 10);
        OrderRequest restingOrderRequest2 = OrderRequest.limit(Side.SELL, 100, 20);
        OrderRequest incomingOrderRequest = OrderRequest.limit(Side.BUY, 100, 25);

        submit(restingOrderRequest1);
        submit(restingOrderRequest2);

        Order incomingOrder = createOrder(incomingOrderRequest);

        List<Trade> trades = engine.submit(incomingOrder).trades();

        // Size of trades.
        assertEquals(2, trades.size());

        // Quantities of trades.
        assertEquals(10, trades.get(0).getQuantity());
        assertEquals(15, trades.get(1).getQuantity());

        // Remaining in the book.
        assertFalse(orderBook.bestAsk().isEmpty());
        assertEquals(5, orderBook.bestAsk().get().getTotalVolume());

        // Incoming order - quantity and addition to the order book.
        assertTrue(orderBook.bestBid().isEmpty());
    }
}