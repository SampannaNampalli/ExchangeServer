import java.io.IOException;
import java.time.Instant;
import java.util.*;

public class Replay {
    private final MatchingEngine engine;
    private final CommandJournal journal;
    private long lastSequenceNumber = 0;
    private long lastNewOrderId = 0;
    private Instant lastTimeStampInstant = null;

    public Replay(MatchingEngine engine, CommandJournal journal) {
        this.engine = engine;
        this.journal = journal;
    }

    // skipped verification of Responses/CancelResults after crash with client since they were already delivered in the live run.
    public void startReplay() throws IOException {
        List<Command> commands = journal.readAll();

        for (Command command: commands) {

            long sequenceNumber = command.getSequenceNumber();
            if (sequenceNumber != (lastSequenceNumber + 1)) {
                throw new IllegalStateException("Sequence numbers are not in order: journal suspected to be corrupted => Current sequence number: " + sequenceNumber + ", last sequence number: " + lastSequenceNumber);
            }
            lastSequenceNumber = sequenceNumber;

            Instant timeStampInstant = command.getTimeStamp();
            if (lastTimeStampInstant == null) {
                lastTimeStampInstant = timeStampInstant;
            }
            else {
                if (timeStampInstant.isBefore(lastTimeStampInstant)) {
                    throw new IllegalStateException("A newer record in the journal contains a timestamp that is older than the timestamp of the previous record.");
                }
            }
            lastTimeStampInstant = timeStampInstant;

            if (command instanceof NewOrderCommand n) {
                lastNewOrderId = n.getOrderId();
                engine.submitNewOrderCommand(n);
            }
            else if (command instanceof CancelOrderCommand c) {
                engine.submitCancelOrderCommand(c);
            }
            else {
                throw new IllegalStateException("Unknown command type: " + command.getClass());
            }
        }
    }

    // To allow sequencer to continue from where it left off.
    public long getLastSequenceNumber() {
        return lastSequenceNumber;
    }
    
    public long getLastNewOrderId() {
        return lastNewOrderId;
    }

    public Instant getLastTimeStampInstant() {
        return lastTimeStampInstant;
    }
}
