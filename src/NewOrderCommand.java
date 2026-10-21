import java.time.Instant;

public class NewOrderCommand {
    private final OrderRequest orderRequest;
    private final long sequenceNumber;
    private final Instant timestamp;
    private final long orderId;

    // new-order
    public NewOrderCommand(OrderRequest orderRequest, long sequenceNumber, Instant timestamp, long orderId) {
        this.orderRequest = orderRequest;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.orderId = orderId;
    }

    public OrderRequest getOrderRequest() {
        return orderRequest;
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
