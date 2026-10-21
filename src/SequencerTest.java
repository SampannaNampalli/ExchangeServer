import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/*

Step 3B: the sequencer.
- One component stamps every command: a sequence number (every command), a timestamp (every command)
  and an order id (new orders only).
- Sequence numbers and order ids both start at 1 and go up by one; a cancel uses a sequence number
  but never an order id.
- The engine uses only the stamped values.

 */
class SequencerTest {

    Sequencer sequencer;

    @BeforeEach
    void setUp() {
        sequencer = new Sequencer();
    }

    static OrderRequest buy(long price, long quantity) {
        return OrderRequest.limit(Side.BUY, price, quantity);
    }

    // ---------- Numbering ----------

    @Test
    void firstNewOrderGetsSequenceOneAndIdOne() {
        NewOrderCommand command = sequencer.createStampedNewOrderCommand(buy(100, 10));

        assertEquals(1, command.getSequenceNumber());
        assertEquals(1, command.getOrderId());                              // 0 would be rejected by Order.
    }

    @Test
    void consecutiveNewOrdersCountUpByOne() {
        NewOrderCommand first = sequencer.createStampedNewOrderCommand(buy(100, 10));
        NewOrderCommand second = sequencer.createStampedNewOrderCommand(buy(101, 10));
        NewOrderCommand third = sequencer.createStampedNewOrderCommand(buy(102, 10));

        assertEquals(1, first.getSequenceNumber());
        assertEquals(2, second.getSequenceNumber());
        assertEquals(3, third.getSequenceNumber());
        assertEquals(1, first.getOrderId());
        assertEquals(2, second.getOrderId());
        assertEquals(3, third.getOrderId());
    }

    @Test
    void cancelUsesASequenceNumberButNoOrderId() {
        NewOrderCommand first = sequencer.createStampedNewOrderCommand(buy(100, 10));
        CancelOrderCommand cancel = sequencer.createStampedCancelOrderCommand(first.getOrderId());
        NewOrderCommand second = sequencer.createStampedNewOrderCommand(buy(101, 10));

        assertEquals(1, first.getSequenceNumber());
        assertEquals(2, cancel.getSequenceNumber());
        assertEquals(3, second.getSequenceNumber());
        assertEquals(2, second.getOrderId());                               // The cancel did not use id 2.
    }

    @Test
    void manyMixedCommandsGetContiguousSequenceNumbersAndStrictlyIncreasingIds() {
        long lastId = 0;

        for (long expectedSeq = 1; expectedSeq <= 1_000; expectedSeq++) {
            if (expectedSeq % 3 == 0) {
                CancelOrderCommand cancel = sequencer.createStampedCancelOrderCommand(lastId);
                assertEquals(expectedSeq, cancel.getSequenceNumber());
            } else {
                NewOrderCommand order = sequencer.createStampedNewOrderCommand(buy(100, 1));
                assertEquals(expectedSeq, order.getSequenceNumber());
                assertEquals(lastId + 1, order.getOrderId());
                lastId = order.getOrderId();
            }
        }
    }

    @Test
    void twoSequencersAreIndependent() {
        Sequencer other = new Sequencer();
        sequencer.createStampedNewOrderCommand(buy(100, 10));
        sequencer.createStampedNewOrderCommand(buy(100, 10));

        NewOrderCommand command = other.createStampedNewOrderCommand(buy(100, 10));

        assertEquals(1, command.getSequenceNumber());
        assertEquals(1, command.getOrderId());
    }

    // ---------- Payload carried unchanged ----------

    @Test
    void newOrderCommandCarriesTheRequestAsGiven() {
        OrderRequest request = OrderRequest.market(Side.SELL, 7);

        NewOrderCommand command = sequencer.createStampedNewOrderCommand(request);

        assertSame(request, command.getOrderRequest());
    }

    @Test
    void cancelCommandCarriesTheTargetIdAsGiven() {
        sequencer.createStampedNewOrderCommand(buy(100, 10));               // Counters now at seq 2, id 2.

        CancelOrderCommand cancel = sequencer.createStampedCancelOrderCommand(42);

        assertEquals(42, cancel.getOrderId());                              // Not a counter value.
    }

    // ---------- Timestamp ----------

    @Test
    void timestampIsTakenWhenTheCommandIsStamped() {
        Instant before = Instant.now();
        NewOrderCommand order = sequencer.createStampedNewOrderCommand(buy(100, 10));
        CancelOrderCommand cancel = sequencer.createStampedCancelOrderCommand(1);
        Instant after = Instant.now();

        for (Instant ts : new Instant[] { order.getTimeStamp(), cancel.getTimeStamp() }) {
            assertNotNull(ts);
            assertFalse(ts.isBefore(before));
            assertFalse(ts.isAfter(after));
        }
    }

    @Test
    void timestampsNeverGoBackwards() {
        Instant previous = Instant.MIN;

        for (int i = 0; i < 10_000; i++) {
            Instant ts = (i % 2 == 0)
                    ? sequencer.createStampedNewOrderCommand(buy(100, 1)).getTimeStamp()
                    : sequencer.createStampedCancelOrderCommand(1).getTimeStamp();
            assertFalse(ts.isBefore(previous));
            previous = ts;
        }
    }

    // ---------- Sequencer + engine ----------

    @Test
    void engineUsesTheSequencerStamps() {
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        NewOrderCommand sell = sequencer.createStampedNewOrderCommand(OrderRequest.limit(Side.SELL, 100, 10));
        NewOrderCommand buy = sequencer.createStampedNewOrderCommand(buy(100, 4));

        engine.submitNewOrderCommand(sell);
        Response response = engine.submitNewOrderCommand(buy);

        assertEquals(2, response.orderId());
        Trade trade = response.trades().get(0);
        assertEquals(2, trade.getBuyOrderId());
        assertEquals(1, trade.getSellOrderId());
        assertEquals(buy.getTimeStamp(), trade.getTimestamp());
    }

    @Test
    void stampedCancelCancelsTheStampedOrder() {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new MatchingEngine(book);
        NewOrderCommand order = sequencer.createStampedNewOrderCommand(buy(100, 10));
        engine.submitNewOrderCommand(order);

        CancelResult result = engine.submitCancelOrderCommand(
                sequencer.createStampedCancelOrderCommand(order.getOrderId()));

        assertEquals(new CancelResult(CancelStatus.CANCELLED, 10), result);
        assertEquals(0, book.getNoOfBids());
    }
}
