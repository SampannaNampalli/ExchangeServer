import java.time.Instant;

public class Sequencer {
    
    private long sequenceNumber;
    private long orderId;
    private Instant previousTimeStampInstant;

    public Sequencer() {
        sequenceNumber = 1;
        orderId = 1;
        previousTimeStampInstant = Instant.now();
    }

    public NewOrderCommand createStampedNewOrderCommand(OrderRequest orderRequest) {

        Instant timeStampInstant = getValidTimeStampInstant();
        return new NewOrderCommand(orderRequest, sequenceNumber++, timeStampInstant, orderId++);        
    }

    public CancelOrderCommand createStampedCancelOrderCommand(long cancelOrderId) {
        Instant timeStampInstant = getValidTimeStampInstant();
        return new CancelOrderCommand(sequenceNumber++, timeStampInstant, cancelOrderId);        
    }

    private Instant getValidTimeStampInstant() {
        
        Instant timeStampInstant = Instant.now();

        if (timeStampInstant.isBefore(previousTimeStampInstant)) {
            timeStampInstant = previousTimeStampInstant;
        }
        previousTimeStampInstant = timeStampInstant;

        return timeStampInstant;
    }

    // For replay -> Restore counters to the appropriate values.
    void setNextSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    void setNextNewOrderId(long newOrderId) {
        orderId = newOrderId;
    }
    
    void setPreviousTimeStampInstant(Instant timeStampInstant) {
        previousTimeStampInstant = timeStampInstant;
    }
}
