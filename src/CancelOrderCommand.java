import java.time.Instant;

public final class CancelOrderCommand implements Command {
    private final long sequenceNumber;
    private final Instant timestamp;
    private final long orderId;

    public CancelOrderCommand(long sequenceNumber, Instant timestamp, long orderId) {
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.orderId = orderId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getTimeStamp() {
        return timestamp;
    }

    public long getOrderId() {
        return orderId;
    }
}
