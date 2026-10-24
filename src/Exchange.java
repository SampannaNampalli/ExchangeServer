import java.io.IOException;
import java.util.Objects;

/*

Step 3C: the one live path. Stamp -> write ahead -> process.

A command reaches the engine only after it is durable in the journal, so every effect a client can see
(trades, responses) belongs to a command that replay will find. Replay (step 3D) feeds journalled commands
straight to the engine and skips the sequencer and the journal, so it never appends a command twice.

 */
public final class Exchange implements AutoCloseable {

    private final Sequencer sequencer;
    private final CommandJournal journal;
    private final MatchingEngine engine;

    public Exchange(Sequencer sequencer, CommandJournal journal, MatchingEngine engine) throws IOException {
        // A fresh sequencer and engine would reuse sequence numbers and ids already in the journal.
        if (!journal.isEmpty()) {
            throw new IllegalStateException("Journal already holds commands; restoring from it is step 3D (replay).");
        }
        this.sequencer = sequencer;
        this.journal = journal;
        this.engine = engine;
    }

    public Response submit(OrderRequest request) throws IOException {
        Objects.requireNonNull(request, "request");     // before stamping: a null must not use up a sequence number

        NewOrderCommand command = sequencer.createStampedNewOrderCommand(request);
        journal.append(command);                        // durable before any effect
        return engine.submitNewOrderCommand(command);
    }

    public CancelResult cancel(long orderId) throws IOException {
        CancelOrderCommand command = sequencer.createStampedCancelOrderCommand(orderId);
        journal.append(command);
        return engine.submitCancelOrderCommand(command);
    }

    @Override
    public void close() throws IOException {
        journal.close();
    }
}
