import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

/*

Step 3C: the write-ahead input log.

File format: one command per record, fields separated by ',', every record ends with '\n' (never "\r\n").
    NEW,<seq>,<timestamp>,<orderId>,<side>,<price>,<quantity>,<type>
    CANCEL,<seq>,<timestamp>,<orderId>
A MARKET order has no price; its price field is "-".

Durability: append() returns only after the record has been forced to disk (fsync).
Recovery:   open() cuts off a torn last record (bytes after the last '\n'). readAll() fails loudly on any
            record that does not parse: after open() the only crash damage is gone, so anything else is
            corruption or a bug.
Failure:    if a write or fsync fails, the journal refuses every later append (fail-stop). Restart to recover.

 */
public final class CommandJournal implements AutoCloseable {

    private static final String NEW = "NEW";
    private static final String CANCEL = "CANCEL";
    private static final String NO_PRICE = "-";
    private static final char SEPARATOR = ',';
    private static final byte END_OF_RECORD = '\n';

    private final Path file;
    private final FileChannel channel;
    private final long truncatedBytesOnOpen;
    private boolean failed;

    private CommandJournal(Path file, FileChannel channel, long truncatedBytesOnOpen) {
        this.file = file;
        this.channel = channel;
        this.truncatedBytesOnOpen = truncatedBytesOnOpen;
    }

    // Opens the journal (creating it if missing), after cutting off a torn last record.
    public static CommandJournal open(Path file) throws IOException {
        long truncated = cutTornTail(file);
        FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND);   // APPEND: never overwrite history
        return new CommandJournal(file, channel, truncated);
    }

    // Returns only once the record is on disk. The caller must not process the command before that.
    public void append(Command command) throws IOException {
        if (failed) {
            throw new IllegalStateException("Journal failed earlier; restart to recover.");
        }

        // Encode first: a command that cannot be encoded writes nothing.
        ByteBuffer record = ByteBuffer.wrap(encode(command).getBytes(UTF_8));

        try {
            while (record.hasRemaining()) {
                channel.write(record);
            }
            channel.force(true);
        } catch (IOException e) {
            // A partial record may now be on disk, and a failed fsync cannot be retried safely.
            failed = true;
            throw e;
        }
    }

    // Every command in the journal, oldest first.
    public List<Command> readAll() throws IOException {
        List<Command> commands = new ArrayList<>();
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        long recordNumber = 0;

        // Read bytes, not lines: readLine() cannot tell a finished last line from a cut-off one.
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            int b;
            while ((b = in.read()) != -1) {
                if (b == END_OF_RECORD) {
                    recordNumber++;
                    commands.add(decode(record.toString(UTF_8), recordNumber));
                    record.reset();
                } else {
                    record.write(b);
                }
            }
        }

        if (record.size() > 0) {
            throw new IllegalStateException("Journal record " + (recordNumber + 1) + " has no end-of-record: \""
                    + record.toString(UTF_8) + "\"");
        }
        return commands;
    }

    public boolean isEmpty() throws IOException {
        return channel.size() == 0;
    }

    // How many bytes of a torn last record open() removed (0 if the file was clean).
    public long getTruncatedBytesOnOpen() {
        return truncatedBytesOnOpen;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // ---------- Recovery ----------

    // Cuts everything after the last '\n' and returns how many bytes were cut.
    private static long cutTornTail(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, CREATE, READ, WRITE)) {
            long size = ch.size();
            long end = endOfLastCompleteRecord(ch, size);
            if (end < size) {
                ch.truncate(end);
                ch.force(true);                         // the cut must be durable before new records follow it
            }
            return size - end;
        }
    }

    // Position just after the last '\n', or 0 if there is none. Scans backwards in blocks.
    private static long endOfLastCompleteRecord(FileChannel ch, long size) throws IOException {
        ByteBuffer block = ByteBuffer.allocate(4096);
        long blockEnd = size;

        while (blockEnd > 0) {
            int length = (int) Math.min(block.capacity(), blockEnd);
            long blockStart = blockEnd - length;

            block.clear().limit(length);
            while (block.hasRemaining()) {
                if (ch.read(block, blockStart + block.position()) < 0) {
                    throw new IOException("Journal shrank while being recovered");
                }
            }
            for (int i = length - 1; i >= 0; i--) {
                if (block.get(i) == END_OF_RECORD) {
                    return blockStart + i + 1;
                }
            }
            blockEnd = blockStart;
        }
        return 0;
    }

    // ---------- Format ----------

    static String encode(Command command) {
        Objects.requireNonNull(command, "command");
        if (command.getTimeStamp() == null) {
            throw new IllegalStateException("Command " + command.getSequenceNumber() + " has no timestamp");
        }

        StringBuilder sb = new StringBuilder();
        if (command instanceof NewOrderCommand n) {
            OrderRequest r = Objects.requireNonNull(n.getOrderRequest(), "orderRequest");
            String price = (r.getOrderType() == OrderType.MARKET) ? NO_PRICE : Long.toString(r.getPrice());
            sb.append(NEW)
                    .append(SEPARATOR).append(n.getSequenceNumber())
                    .append(SEPARATOR).append(n.getTimeStamp())
                    .append(SEPARATOR).append(n.getOrderId())
                    .append(SEPARATOR).append(r.getSide().name())
                    .append(SEPARATOR).append(price)
                    .append(SEPARATOR).append(r.getQuantity())
                    .append(SEPARATOR).append(r.getOrderType().name());
        } else if (command instanceof CancelOrderCommand c) {
            sb.append(CANCEL)
                    .append(SEPARATOR).append(c.getSequenceNumber())
                    .append(SEPARATOR).append(c.getTimeStamp())
                    .append(SEPARATOR).append(c.getOrderId());
        } else {
            throw new IllegalStateException("Unknown command type: " + command.getClass());
        }
        return sb.append((char) END_OF_RECORD).toString();
    }

    static Command decode(String record, long recordNumber) {
        String[] f = record.split(String.valueOf(SEPARATOR), -1);  // -1 keeps trailing empty fields
        try {
            return switch (f[0]) {
                case NEW -> {
                    requireFieldCount(f, 8);
                    long seq = Long.parseLong(f[1]);
                    Instant timestamp = Instant.parse(f[2]);
                    long orderId = Long.parseLong(f[3]);
                    Side side = Side.valueOf(f[4]);
                    long quantity = Long.parseLong(f[6]);
                    OrderType type = OrderType.valueOf(f[7]);

                    OrderRequest request;
                    if (type == OrderType.MARKET) {
                        if (!f[5].equals(NO_PRICE)) {
                            throw new IllegalArgumentException("MARKET order with a price");
                        }
                        request = OrderRequest.market(side, quantity);
                    } else {
                        request = new OrderRequest(side, Long.parseLong(f[5]), quantity, type);
                    }
                    yield new NewOrderCommand(request, seq, timestamp, orderId);
                }
                case CANCEL -> {
                    requireFieldCount(f, 4);
                    yield new CancelOrderCommand(Long.parseLong(f[1]), Instant.parse(f[2]), Long.parseLong(f[3]));
                }
                default -> throw new IllegalArgumentException("unknown record type");
            };
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // Includes NumberFormatException, enum valueOf and OrderRequest's own validation.
            throw new IllegalStateException("Corrupt journal record " + recordNumber + ": \"" + record + "\"", e);
        }
    }

    private static void requireFieldCount(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " fields, found " + fields.length);
        }
    }
}
