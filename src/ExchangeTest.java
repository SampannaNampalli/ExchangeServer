import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/*

Step 3C: the one live path. Stamp -> write ahead -> process.
- Every command the engine processes is in the journal first, stamped exactly as the engine saw it.
- If the journal cannot take a command, the engine never sees it.
- Stateless rejects (invalid requests) never reach the journal.

 */
class ExchangeTest {

    @TempDir
    Path dir;

    OrderBook orderBook;
    CommandJournal journal;
    Exchange exchange;

    @BeforeEach
    void setUp() throws IOException {
        orderBook = new OrderBook();
        journal = CommandJournal.open(dir.resolve("journal.log"));
        exchange = new Exchange(new Sequencer(), journal, new MatchingEngine(orderBook));
    }

    @AfterEach
    void tearDown() throws IOException {
        exchange.close();                                                       // Windows: @TempDir cleanup needs it
    }

    @Test
    void submitJournalsTheStampedCommandAndReturnsTheEnginesResponse() throws IOException {
        Response response = exchange.submit(OrderRequest.limit(Side.BUY, 100, 10));

        assertEquals(new Response(1, List.of(), OrderStatus.RESTED, 10), response);
        List<Command> journalled = journal.readAll();
        assertEquals(1, journalled.size());
        NewOrderCommand c = (NewOrderCommand) journalled.get(0);
        assertEquals(1, c.getSequenceNumber());
        assertEquals(1, c.getOrderId());
        assertEquals(100, c.getOrderRequest().getPrice());
    }

    @Test
    void tradeTimestampEqualsTheJournalledTimestamp() throws IOException {
        exchange.submit(OrderRequest.limit(Side.SELL, 100, 10));
        Response response = exchange.submit(OrderRequest.limit(Side.BUY, 100, 4));

        // Replay will rebuild this trade from the journal, so the two must agree.
        assertEquals(journal.readAll().get(1).getTimeStamp(), response.trades().get(0).getTimestamp());
    }

    @Test
    void cancelIsJournalledWithItsTarget() throws IOException {
        exchange.submit(OrderRequest.limit(Side.BUY, 100, 10));

        assertEquals(new CancelResult(CancelStatus.CANCELLED, 10), exchange.cancel(1));

        CancelOrderCommand c = (CancelOrderCommand) journal.readAll().get(1);
        assertEquals(2, c.getSequenceNumber());
        assertEquals(1, c.getOrderId());
    }

    @Test
    void stateDependentRejectsAreJournalledToo() throws IOException {
        // D13: the journal cannot know in advance that these will be rejected by the engine.
        exchange.submit(OrderRequest.limit(Side.SELL, 100, 10));
        Response postOnly = exchange.submit(new OrderRequest(Side.BUY, 100, 5, OrderType.POST_ONLY));
        CancelResult unknown = exchange.cancel(999);

        assertEquals(OrderStatus.REJECTED, postOnly.orderStatus());
        assertEquals(CancelStatus.UNKNOWN_ORDER, unknown.status());
        assertEquals(3, journal.readAll().size());
    }

    @Test
    void journalOrderIsProcessingOrderWithContiguousSequenceNumbers() throws IOException {
        exchange.submit(OrderRequest.limit(Side.SELL, 101, 5));
        exchange.submit(OrderRequest.market(Side.BUY, 2));
        exchange.cancel(1);
        exchange.submit(new OrderRequest(Side.BUY, 99, 3, OrderType.IOC));

        List<Command> journalled = journal.readAll();
        for (int i = 0; i < journalled.size(); i++) {
            assertEquals(i + 1, journalled.get(i).getSequenceNumber());
        }
        assertInstanceOf(CancelOrderCommand.class, journalled.get(2));
    }

    @Test
    void ifTheJournalFailsTheEngineNeverSeesTheCommand() throws IOException {
        journal.close();                                                        // the next append will fail

        assertThrows(IOException.class, () -> exchange.submit(OrderRequest.limit(Side.BUY, 100, 10)));

        assertEquals(0, orderBook.getNoOfBids());                               // not processed
        assertThrows(IllegalStateException.class,                               // fail-stop from now on
                () -> exchange.submit(OrderRequest.limit(Side.BUY, 100, 10)));
        assertEquals(0, orderBook.getNoOfBids());
    }

    @Test
    void statelessRejectsNeverReachTheJournal() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> exchange.submit(OrderRequest.limit(Side.BUY, 100, 0)));   // fails building the request

        assertTrue(journal.isEmpty());
    }

    @Test
    void nullRequestUsesNoSequenceNumber() throws IOException {
        assertThrows(NullPointerException.class, () -> exchange.submit(null));

        exchange.submit(OrderRequest.limit(Side.BUY, 100, 10));
        assertEquals(1, journal.readAll().get(0).getSequenceNumber());
        assertEquals(1, journal.readAll().size());
    }

    @Test
    void journalSurvivesARestartUnchanged() throws IOException {
        exchange.submit(OrderRequest.limit(Side.SELL, 100, 10));
        exchange.submit(OrderRequest.limit(Side.BUY, 100, 4));
        exchange.cancel(1);
        String before = Files.readString(dir.resolve("journal.log"), UTF_8);
        exchange.close();

        try (CommandJournal reopened = CommandJournal.open(dir.resolve("journal.log"))) {
            assertEquals(3, reopened.readAll().size());
            assertEquals(before, Files.readString(dir.resolve("journal.log"), UTF_8));
        }
    }
}
