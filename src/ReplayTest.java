import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/*

Step 3D: replay.
- Restarting on a journal rebuilds the identical book: same orders, levels, time priority and remaining quantities.
- The journal alone reproduces every live output (responses, trades, cancel results).
- After a restart, sequence numbers, order ids and timestamps continue: nothing reused, nothing goes backwards.
- Replay writes nothing to the journal.
- A broken history (gap, repeat, backwards timestamp, repeated id) fails loudly, and the journal is closed.

 */
class ReplayTest {

    @TempDir
    Path dir;

    Path file() {
        return dir.resolve("journal.log");
    }

    static Exchange start(Path file, OrderBook book) throws IOException {
        return new Exchange(new Sequencer(), CommandJournal.open(file), new MatchingEngine(book));
    }

    // A mixed live session: every order type, partial fills, cancels and both kinds of state-dependent reject.
    // An OrderRequest is a submit; a Long is a cancel of that order id.
    static final List<Object> SESSION = List.of(
            OrderRequest.limit(Side.SELL, 101, 5),                          // id 1 rests
            OrderRequest.limit(Side.SELL, 101, 7),                          // id 2 rests behind id 1
            OrderRequest.limit(Side.SELL, 102, 10),                         // id 3 rests
            OrderRequest.limit(Side.BUY, 99, 4),                            // id 4 rests
            OrderRequest.limit(Side.BUY, 98, 6),                            // id 5 rests
            new OrderRequest(Side.BUY, 101, 8, OrderType.IOC),              // id 6: fills 5 (id 1) + 3 (id 2)
            4L,                                                             // cancel id 4
            new OrderRequest(Side.BUY, 102, 100, OrderType.FOK),            // id 7: not enough, cancelled
            new OrderRequest(Side.SELL, 98, 3, OrderType.POST_ONLY),        // id 8: would cross 98, rejected
            OrderRequest.market(Side.SELL, 2),                              // id 9: fills 2 of id 5
            OrderRequest.limit(Side.BUY, 102, 20),                          // id 10: fills 4 (id 2) + 10 (id 3), rests 6
            999L,                                                           // cancel of an unknown id
            OrderRequest.limit(Side.SELL, 100, 1));                         // id 11: fills 1 of id 10

    static List<String> runSession(Exchange ex, List<Object> steps) throws IOException {
        List<String> outputs = new ArrayList<>();
        for (Object step : steps) {
            if (step instanceof OrderRequest r) {
                outputs.add(render(ex.submit(r)));
            } else {
                outputs.add(render(ex.cancel((Long) step)));
            }
        }
        return outputs;
    }

    static String render(Response r) {
        StringBuilder sb = new StringBuilder("R " + r.orderId() + " " + r.orderStatus() + " " + r.remainingQuantity());
        for (Trade t : r.trades()) {
            sb.append(" | ").append(t.getQuantity()).append('@').append(t.getPrice())
                    .append(" b").append(t.getBuyOrderId()).append(" s").append(t.getSellOrderId())
                    .append(' ').append(t.getTimestamp());
        }
        return sb.toString();
    }

    static String render(CancelResult c) {
        return "C " + c.status() + " " + c.cancelledQuantity();
    }

    // Every level, best first, with every order in time priority: id, remaining/original, timestamp.
    static List<String> snapshot(OrderBook book) {
        List<String> out = new ArrayList<>();
        book.askPriceLevelIterator().forEachRemaining(l -> out.add("ASK " + describe(l)));
        book.bidPriceLevelIterator().forEachRemaining(l -> out.add("BID " + describe(l)));
        return out;
    }

    static String describe(PriceLevel level) {
        StringBuilder sb = new StringBuilder(level.getPrice() + " vol=" + level.getTotalVolume()
                + " n=" + level.getNoOfOrders() + " [");
        for (Order o : level.ordersHeadToTail()) {
            sb.append(' ').append(o.getOrderId()).append(':').append(o.getRemainingQuantity())
                    .append('/').append(o.getOriginalQuantity()).append('@').append(o.getTimestamp());
        }
        return sb.append(" ]").toString();
    }

    static String newRecord(long seq, String ts, long id) {
        return "NEW," + seq + "," + ts + "," + id + ",BUY,100,10,LIMIT\n";
    }

    static String cancelRecord(long seq, String ts, long target) {
        return "CANCEL," + seq + "," + ts + "," + target + "\n";
    }

    // ---------- Same state, same outputs ----------

    @Test
    void restartRebuildsTheIdenticalBook() throws IOException {
        OrderBook live = new OrderBook();
        try (Exchange ex = start(file(), live)) {
            runSession(ex, SESSION);
        }

        OrderBook replayed = new OrderBook();
        try (Exchange ignored = start(file(), replayed)) {
            assertEquals(snapshot(live), snapshot(replayed));
            assertFalse(snapshot(replayed).isEmpty());
        }
    }

    @Test
    void theJournalAloneReproducesEveryLiveOutput() throws IOException {
        List<String> liveOutputs;
        try (Exchange ex = start(file(), new OrderBook())) {
            liveOutputs = runSession(ex, SESSION);
        }

        // What replay does, but keeping the outputs: every journalled command into a fresh engine.
        MatchingEngine fresh = new MatchingEngine(new OrderBook());
        List<String> replayOutputs = new ArrayList<>();
        try (CommandJournal journal = CommandJournal.open(file())) {
            for (Command c : journal.readAll()) {
                if (c instanceof NewOrderCommand n) {
                    replayOutputs.add(render(fresh.submitNewOrderCommand(n)));
                } else {
                    replayOutputs.add(render(fresh.submitCancelOrderCommand((CancelOrderCommand) c)));
                }
            }
        }

        assertEquals(liveOutputs, replayOutputs);                       // trades, timestamps and rejects included
    }

    @Test
    void timePriorityIsRestored() throws IOException {
        try (Exchange ex = start(file(), new OrderBook())) {
            ex.submit(OrderRequest.limit(Side.SELL, 100, 5));               // id 1: first in the queue
            ex.submit(OrderRequest.limit(Side.SELL, 100, 5));               // id 2: behind it
        }

        try (Exchange ex = start(file(), new OrderBook())) {
            Response r = ex.submit(OrderRequest.limit(Side.BUY, 100, 5));

            assertEquals(1, r.trades().get(0).getSellOrderId());            // the older order still trades first
        }
    }

    @Test
    void replayWritesNothingToTheJournal() throws IOException {
        try (Exchange ex = start(file(), new OrderBook())) {
            runSession(ex, SESSION);
        }
        byte[] before = Files.readAllBytes(file());

        try (Exchange ignored = start(file(), new OrderBook())) {
            assertArrayEquals(before, Files.readAllBytes(file()));
        }
    }

    // ---------- Continue, don't repeat ----------

    @Test
    void numberingContinuesAfterARestart() throws IOException {
        try (Exchange ex = start(file(), new OrderBook())) {
            ex.submit(OrderRequest.limit(Side.BUY, 100, 10));               // seq 1, id 1
            ex.submit(OrderRequest.limit(Side.BUY, 99, 10));                // seq 2, id 2
        }

        try (Exchange ex = start(file(), new OrderBook())) {
            assertEquals(3, ex.submit(OrderRequest.limit(Side.BUY, 98, 10)).orderId());
            ex.cancel(1);
        }

        try (CommandJournal journal = CommandJournal.open(file())) {
            List<Command> all = journal.readAll();
            assertEquals(4, all.size());
            assertEquals(3, all.get(2).getSequenceNumber());
            assertEquals(4, all.get(3).getSequenceNumber());
        }
    }

    @Test
    void nextOrderIdComesFromTheLastNewOrderNotTheLastRecord() throws IOException {
        // Quiz question 7: the last record is a cancel whose id (3) is a target, not an issued id.
        Files.writeString(file(), newRecord(1, "2026-01-01T10:00:00Z", 3)
                + newRecord(2, "2026-01-01T10:00:01Z", 5)
                + cancelRecord(3, "2026-01-01T10:00:02Z", 3), UTF_8);

        try (Exchange ex = start(file(), new OrderBook())) {
            assertEquals(6, ex.submit(OrderRequest.limit(Side.SELL, 200, 1)).orderId());
        }
    }

    @Test
    void journalWithOnlyCancelsStartsOrderIdsAtOne() throws IOException {
        Files.writeString(file(), cancelRecord(1, "2026-01-01T10:00:00Z", 42)
                + cancelRecord(2, "2026-01-01T10:00:01Z", 43), UTF_8);

        try (Exchange ex = start(file(), new OrderBook())) {
            assertEquals(1, ex.submit(OrderRequest.limit(Side.BUY, 100, 1)).orderId());
        }
        try (CommandJournal journal = CommandJournal.open(file())) {
            assertEquals(3, journal.readAll().get(2).getSequenceNumber());
        }
    }

    @Test
    void manyRestartsInARowKeepTheHistoryValid() throws IOException {
        // The probe from the review: one restart used to make the journal unreplayable.
        OrderBook reference = new OrderBook();
        try (Exchange single = start(dir.resolve("reference.log"), reference)) {
            runSession(single, SESSION);
        }

        OrderBook last = null;
        for (int from = 0; from < SESSION.size(); from += 3) {             // restart every 3 steps
            last = new OrderBook();
            try (Exchange ex = start(file(), last)) {
                runSession(ex, SESSION.subList(from, Math.min(from + 3, SESSION.size())));
            }
        }

        OrderBook afterAll = new OrderBook();
        try (Exchange ignored = start(file(), afterAll)) {
            assertEquals(snapshot(last), snapshot(afterAll));
            // Same orders and quantities as one uninterrupted session (timestamps differ, so compare without them).
            assertEquals(withoutTimestamps(snapshot(reference)), withoutTimestamps(snapshot(afterAll)));
        }
    }

    static List<String> withoutTimestamps(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(l.replaceAll("@\\S+", ""));
        return out;
    }

    @Test
    void timestampsNeverGoBackwardsAcrossARestart() throws IOException {
        // The journal's last stamp is far in the future, as if the clock moved back during the downtime.
        Instant future = Instant.parse("2100-01-01T00:00:00Z");
        Files.writeString(file(), newRecord(1, future.toString(), 1), UTF_8);

        try (Exchange ex = start(file(), new OrderBook())) {
            ex.submit(OrderRequest.limit(Side.SELL, 200, 1));
        }

        try (CommandJournal journal = CommandJournal.open(file())) {
            Instant next = journal.readAll().get(1).getTimeStamp();
            assertFalse(next.isBefore(future), "went backwards: " + next);
        }
    }

    @Test
    void journalHoldingOnlyATornRecordStartsFresh() throws IOException {
        Files.writeString(file(), "NEW,1,2026-01-0", UTF_8);              // quiz question 8

        try (Exchange ex = start(file(), new OrderBook())) {
            assertEquals(1, ex.submit(OrderRequest.limit(Side.BUY, 100, 1)).orderId());
        }
    }

    // ---------- A broken history fails loudly ----------

    void assertRestartFails(String journalContent, String expectedInMessage) throws IOException {
        Files.writeString(file(), journalContent, UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> start(file(), new OrderBook()));
        if (expectedInMessage != null) {
            assertTrue(e.getMessage().contains(expectedInMessage), e.getMessage());
        }
    }

    @Test
    void gapInSequenceNumbersFailsLoudly() throws IOException {
        assertRestartFails(newRecord(1, "2026-01-01T10:00:00Z", 1)
                + newRecord(2, "2026-01-01T10:00:01Z", 2)
                + newRecord(4, "2026-01-01T10:00:02Z", 3), "4");
    }

    @Test
    void repeatedSequenceNumberFailsLoudly() throws IOException {
        assertRestartFails(newRecord(1, "2026-01-01T10:00:00Z", 1)
                + cancelRecord(1, "2026-01-01T10:00:01Z", 1), null);
    }

    @Test
    void journalNotStartingAtOneFailsLoudly() throws IOException {
        assertRestartFails(newRecord(2, "2026-01-01T10:00:00Z", 1), null);
    }

    @Test
    void timestampGoingBackwardsInTheJournalFailsLoudly() throws IOException {
        assertRestartFails(newRecord(1, "2026-01-01T10:00:05Z", 1)
                + newRecord(2, "2026-01-01T10:00:04Z", 2), null);
    }

    @Test
    void repeatedOrderIdInTheJournalFailsLoudly() throws IOException {
        // Exactly what the off-by-one used to write: caught by the engine's id guard during replay.
        assertRestartFails(newRecord(1, "2026-01-01T10:00:00Z", 1)
                + newRecord(2, "2026-01-01T10:00:01Z", 1), null);
    }

    @Test
    void failedReplayClosesTheJournal() throws IOException {
        Files.writeString(file(), newRecord(2, "2026-01-01T10:00:00Z", 1), UTF_8);
        CommandJournal journal = CommandJournal.open(file());

        assertThrows(IllegalStateException.class,
                () -> new Exchange(new Sequencer(), journal, new MatchingEngine(new OrderBook())));

        assertThrows(ClosedChannelException.class, journal::isEmpty);      // nobody else could have closed it
    }
}
