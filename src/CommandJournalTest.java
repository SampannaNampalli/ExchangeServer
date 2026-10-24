import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.*;

/*

Step 3C: the write-ahead input log.
- Every command reads back exactly as written, in the same order (round trip).
- Reopening never loses or overwrites earlier records.
- A torn last record (no '\n') is cut off on open; any other bad record fails loudly.
- After a failed write the journal refuses every later append (fail-stop).

Not testable here: that force() really reaches the disk. Removing it fails no test (see the mutation notes
in New Learnings/02).

 */
class CommandJournalTest {

    static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-01-01T10:00:05.123456789Z");

    @TempDir
    Path dir;

    Path file() {
        return dir.resolve("journal.log");
    }

    static NewOrderCommand newOrder(long seq, Instant ts, long id, OrderRequest request) {
        return new NewOrderCommand(request, seq, ts, id);
    }

    // Commands have no equals(); this text form compares every field.
    static String describe(Command c) {
        if (c instanceof NewOrderCommand n) {
            OrderRequest r = n.getOrderRequest();
            return "NEW seq=" + n.getSequenceNumber() + " ts=" + n.getTimeStamp() + " id=" + n.getOrderId()
                    + " " + r.getSide() + " price=" + r.getPrice() + " qty=" + r.getQuantity() + " " + r.getOrderType();
        }
        CancelOrderCommand x = (CancelOrderCommand) c;
        return "CANCEL seq=" + x.getSequenceNumber() + " ts=" + x.getTimeStamp() + " id=" + x.getOrderId();
    }

    static List<String> describeAll(List<Command> commands) {
        List<String> out = new ArrayList<>();
        for (Command c : commands) out.add(describe(c));
        return out;
    }

    List<Command> appendAllAndReadBack(List<Command> commands) throws IOException {
        try (CommandJournal journal = CommandJournal.open(file())) {
            for (Command c : commands) journal.append(c);
            return journal.readAll();
        }
    }

    // ---------- Round trip ----------

    @Test
    void everyOrderTypeAndSideRoundTrips() throws IOException {
        List<Command> written = List.of(
                newOrder(1, T1, 1, OrderRequest.limit(Side.BUY, 100, 10)),
                newOrder(2, T1, 2, new OrderRequest(Side.SELL, 101, 5, OrderType.IOC)),
                newOrder(3, T1, 3, new OrderRequest(Side.BUY, 99, 7, OrderType.FOK)),
                newOrder(4, T1, 4, new OrderRequest(Side.SELL, 102, 3, OrderType.POST_ONLY)),
                newOrder(5, T1, 5, OrderRequest.market(Side.BUY, 8)),
                newOrder(6, T1, 6, OrderRequest.market(Side.SELL, 9)));

        assertEquals(describeAll(written), describeAll(appendAllAndReadBack(written)));
    }

    @Test
    void cancelRoundTrips() throws IOException {
        List<Command> written = List.of(new CancelOrderCommand(7, T2, 42));

        assertEquals(describeAll(written), describeAll(appendAllAndReadBack(written)));
    }

    @Test
    void timestampKeepsItsNanoseconds() throws IOException {
        Command read = appendAllAndReadBack(List.of(newOrder(1, T2, 1, OrderRequest.limit(Side.BUY, 100, 10)))).get(0);

        assertEquals(T2, read.getTimeStamp());
        assertEquals(123_456_789, read.getTimeStamp().getNano());
    }

    @Test
    void extremeValuesRoundTrip() throws IOException {
        List<Command> written = List.of(
                newOrder(Long.MAX_VALUE - 1, Instant.EPOCH, Long.MAX_VALUE,
                        OrderRequest.limit(Side.SELL, Order.MAX_PRICE, Order.MAX_ORDER_QUANTITY)),
                newOrder(Long.MAX_VALUE, Instant.EPOCH, 1, OrderRequest.limit(Side.BUY, 1, 1)));

        assertEquals(describeAll(written), describeAll(appendAllAndReadBack(written)));
    }

    @Test
    void mixedCommandsComeBackInOrderAndAsTheRightKind() throws IOException {
        List<Command> written = List.of(
                newOrder(1, T1, 1, OrderRequest.limit(Side.BUY, 100, 10)),
                new CancelOrderCommand(2, T1, 1),
                newOrder(3, T2, 2, OrderRequest.market(Side.SELL, 4)),
                new CancelOrderCommand(4, T2, 2));

        List<Command> read = appendAllAndReadBack(written);

        assertInstanceOf(NewOrderCommand.class, read.get(0));
        assertInstanceOf(CancelOrderCommand.class, read.get(1));
        assertInstanceOf(NewOrderCommand.class, read.get(2));
        assertInstanceOf(CancelOrderCommand.class, read.get(3));
        assertEquals(describeAll(written), describeAll(read));
    }

    @Test
    void readBackMarketOrderIsAValidMarketRequest() throws IOException {
        NewOrderCommand read = (NewOrderCommand) appendAllAndReadBack(
                List.of(newOrder(1, T1, 1, OrderRequest.market(Side.BUY, 8)))).get(0);

        assertEquals(OrderType.MARKET, read.getOrderRequest().getOrderType());
        assertEquals(Order.NO_PRICE, read.getOrderRequest().getPrice());
    }

    // ---------- Exact file format ----------

    @Test
    void recordsHaveTheDocumentedFormatAndEndWithLf() throws IOException {
        appendAllAndReadBack(List.of(
                newOrder(1, T1, 1, OrderRequest.limit(Side.BUY, 100, 10)),
                newOrder(2, T1, 2, OrderRequest.market(Side.SELL, 4)),
                new CancelOrderCommand(3, T1, 1)));

        assertEquals("NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT\n"
                        + "NEW,2,2026-01-01T10:00:00Z,2,SELL,-,4,MARKET\n"      // no price: "-", never -1
                        + "CANCEL,3,2026-01-01T10:00:00Z,1\n",                  // '\n' only, even on Windows
                Files.readString(file(), UTF_8));
    }

    @Test
    void appendedRecordIsInTheFileAsSoonAsAppendReturns() throws IOException {
        try (CommandJournal journal = CommandJournal.open(file())) {
            journal.append(new CancelOrderCommand(1, T1, 5));

            // Read through a different handle while the journal is still open: nothing waits in a Java buffer.
            assertEquals("CANCEL,1,2026-01-01T10:00:00Z,5\n", Files.readString(file(), UTF_8));
        }
    }

    // ---------- Opening, restarting ----------

    @Test
    void openCreatesAMissingFileAndStartsEmpty() throws IOException {
        assertFalse(Files.exists(file()));

        try (CommandJournal journal = CommandJournal.open(file())) {
            assertTrue(Files.exists(file()));
            assertTrue(journal.isEmpty());
            assertEquals(List.of(), journal.readAll());
            assertEquals(0, journal.getTruncatedBytesOnOpen());
        }
    }

    @Test
    void reopeningKeepsEarlierRecordsAndAppendsAfterThem() throws IOException {
        try (CommandJournal journal = CommandJournal.open(file())) {
            journal.append(newOrder(1, T1, 1, OrderRequest.limit(Side.BUY, 100, 10)));
        }
        try (CommandJournal journal = CommandJournal.open(file())) {            // "restart"
            assertFalse(journal.isEmpty());
            journal.append(new CancelOrderCommand(2, T2, 1));

            List<String> read = describeAll(journal.readAll());
            assertEquals(2, read.size());
            assertTrue(read.get(0).startsWith("NEW seq=1"));
            assertTrue(read.get(1).startsWith("CANCEL seq=2"));
        }
    }

    @Test
    void readAllCanBeCalledRepeatedlyAndChangesNothing() throws IOException {
        try (CommandJournal journal = CommandJournal.open(file())) {
            journal.append(new CancelOrderCommand(1, T1, 5));
            String before = Files.readString(file(), UTF_8);

            assertEquals(describeAll(journal.readAll()), describeAll(journal.readAll()));
            assertEquals(before, Files.readString(file(), UTF_8));
        }
    }

    // ---------- Recovery: torn last record ----------

    @Test
    void tornLastRecordIsCutOffOnOpen() throws IOException {
        String good = "NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT\n";
        String torn = "CANCEL,2,2026-01-01T10:0";                               // crash mid-append
        Files.writeString(file(), good + torn, UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            assertEquals(torn.length(), journal.getTruncatedBytesOnOpen());
            assertEquals(good, Files.readString(file(), UTF_8));
            assertEquals(1, journal.readAll().size());
        }
    }

    @Test
    void tornRecordThatLooksValidIsStillCutOff() throws IOException {
        // "CANCEL,2,...,12" cut after "1" would parse as a cancel of order 1. Only the missing '\n' tells.
        String good = "NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT\n";
        Files.writeString(file(), good + "CANCEL,2,2026-01-01T10:00:00Z,1", UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            List<Command> read = journal.readAll();
            assertEquals(1, read.size());
            assertInstanceOf(NewOrderCommand.class, read.get(0));
        }
    }

    @Test
    void fileHoldingOnlyATornRecordBecomesEmpty() throws IOException {
        Files.writeString(file(), "NEW,1,2026-01-0", UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            assertEquals(15, journal.getTruncatedBytesOnOpen());
            assertTrue(journal.isEmpty());
            assertEquals(0, Files.size(file()));
        }
    }

    @Test
    void nextAppendAfterRecoveryStartsOnAFreshRecord() throws IOException {
        Files.writeString(file(), "NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT\nCANCEL,2,20", UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            journal.append(new CancelOrderCommand(2, T1, 1));

            assertEquals(2, journal.readAll().size());                          // not glued to the torn bytes
        }
    }

    @Test
    void tornTailLongerThanOneScanBlockIsFound() throws IOException {
        String good = "CANCEL,1,2026-01-01T10:00:00Z,5\n";
        Files.writeString(file(), good + "x".repeat(10_000), UTF_8);            // > 4096-byte scan block

        try (CommandJournal journal = CommandJournal.open(file())) {
            assertEquals(10_000, journal.getTruncatedBytesOnOpen());
            assertEquals(good, Files.readString(file(), UTF_8));
        }
    }

    @Test
    void cleanFileIsNotTouchedOnOpen() throws IOException {
        String content = "CANCEL,1,2026-01-01T10:00:00Z,5\n";
        Files.writeString(file(), content, UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            assertEquals(0, journal.getTruncatedBytesOnOpen());
            assertEquals(content, Files.readString(file(), UTF_8));
        }
    }

    // ---------- Corruption fails loudly ----------

    @Test
    void corruptRecordInTheMiddleFailsLoudlyWithItsNumber() throws IOException {
        Files.writeString(file(), "CANCEL,1,2026-01-01T10:00:00Z,5\n"
                + "CANCEL,2,2026-01-01T10:00:00Z,abc\n"
                + "CANCEL,3,2026-01-01T10:00:00Z,7\n", UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            IllegalStateException e = assertThrows(IllegalStateException.class, journal::readAll);
            assertTrue(e.getMessage().contains("record 2"), e.getMessage());
        }
    }

    @Test
    void completeButCorruptLastRecordIsNotSilentlyDropped() throws IOException {
        // It has its '\n', so it was fully written: dropping it could lose a processed command.
        String content = "CANCEL,1,2026-01-01T10:00:00Z,5\nCANCEL,2,garbage,7\n";
        Files.writeString(file(), content, UTF_8);

        try (CommandJournal journal = CommandJournal.open(file())) {
            assertEquals(0, journal.getTruncatedBytesOnOpen());
            assertThrows(IllegalStateException.class, journal::readAll);
            assertEquals(content, Files.readString(file(), UTF_8));
        }
    }

    @Test
    void everyKindOfBadRecordIsRejected() throws IOException {
        List<String> bad = List.of(
                "",                                                          // empty record
                "HELLO,1,2026-01-01T10:00:00Z,5",                            // unknown kind
                "new,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT",             // kinds are case-sensitive
                "CANCEL,1,2026-01-01T10:00:00Z",                             // too few fields
                "CANCEL,1,2026-01-01T10:00:00Z,5,",                          // trailing empty field
                "CANCEL,1,2026-01-01T10:00:00Z,5\r",                         // Windows line ending
                "CANCEL,x,2026-01-01T10:00:00Z,5",                           // bad number
                "CANCEL,1,yesterday,5",                                      // bad timestamp
                "NEW,1,2026-01-01T10:00:00Z,1,buy,100,10,LIMIT",             // enum is case-sensitive
                "NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,STOP",              // unknown order type
                "NEW,1,2026-01-01T10:00:00Z,1,BUY,-,10,LIMIT",               // LIMIT without a price
                "NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,MARKET",            // MARKET with a price
                "NEW,1,2026-01-01T10:00:00Z,1,BUY,-1,10,MARKET",             // the sentinel is not the format
                "NEW,1,2026-01-01T10:00:00Z,1,BUY,100,0,LIMIT");             // OrderRequest validation

        for (String record : bad) {
            Path f = dir.resolve("bad" + bad.indexOf(record) + ".log");
            Files.writeString(f, record + "\n", UTF_8);
            try (CommandJournal journal = CommandJournal.open(f)) {
                assertThrows(IllegalStateException.class, journal::readAll, "accepted: \"" + record + "\"");
            }
        }
    }

    @Test
    void readAllRefusesAnUnterminatedTailWrittenAfterOpen() throws IOException {
        try (CommandJournal journal = CommandJournal.open(file())) {
            journal.append(new CancelOrderCommand(1, T1, 5));
            Files.writeString(file(), "CANCEL,2,2026", UTF_8, APPEND);         // someone else wrote half a record

            assertThrows(IllegalStateException.class, journal::readAll);
        }
    }

    // ---------- Nothing bad reaches the file; fail-stop ----------

    @Test
    void commandWithoutTimestampIsRefusedBeforeAnythingIsWritten() throws IOException {
        try (CommandJournal journal = CommandJournal.open(file())) {
            assertThrows(IllegalStateException.class,
                    () -> journal.append(newOrder(1, null, 1, OrderRequest.limit(Side.BUY, 100, 10))));
            assertThrows(NullPointerException.class, () -> journal.append(newOrder(1, T1, 1, null)));
            assertThrows(NullPointerException.class, () -> journal.append(null));

            assertTrue(journal.isEmpty());
            journal.append(new CancelOrderCommand(1, T1, 5));                   // not failed: still usable
            assertEquals(1, journal.readAll().size());
        }
    }

    @Test
    void afterAFailedWriteEveryLaterAppendIsRefused() throws IOException {
        CommandJournal journal = CommandJournal.open(file());
        journal.close();                                                        // makes the next write fail

        assertThrows(IOException.class, () -> journal.append(new CancelOrderCommand(1, T1, 5)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> journal.append(new CancelOrderCommand(2, T1, 5)));
        assertTrue(e.getMessage().contains("failed earlier"), e.getMessage());
    }
}
