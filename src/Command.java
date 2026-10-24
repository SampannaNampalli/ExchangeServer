import java.time.Instant;

// A stamped input command: everything the engine needs to process it.
// Sealed: these two are the only kinds, so code that receives "a command" can handle every kind.
public sealed interface Command permits NewOrderCommand, CancelOrderCommand {

    long getSequenceNumber();

    Instant getTimeStamp();
}
