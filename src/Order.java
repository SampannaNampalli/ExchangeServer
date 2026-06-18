import java.time.Instant;
import java.util.*;

public class Order {
    private final long orderId;
    private final Side side;
    private final long price;
    private final long originalQuantity;
    private final Instant timestamp; // exchangeTimestamp?

    private long filledQuantity;

    public Order(long orderId, Side side, long price, long originalQuantity, Instant timestamp) {

        if (orderId < 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (price < 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (originalQuantity < 0) {
            throw new IllegalArgumentException("Original quantity must be positive");
        }
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.originalQuantity = originalQuantity;
        this.timestamp = timestamp;
    }

    public long getOrderId() {
        return this.orderId;
    }

    public Side getSide() {
        return this.side;
    }

    public long getPrice() {
        return this.price;
    }

    public long getOriginalQuantity() {
        return this.originalQuantity;
    }

    public Instant getTimestamp() {
        return this.timestamp;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public void fill(long quantity) {

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Fill quantity must be positive"
            );
        }

        if (quantity > getRemainingQuantity()) {
            throw new IllegalArgumentException(
                    "Cannot overfill order"
            );
        }
        else {
            filledQuantity += quantity;
        }
    }

    public long getRemainingQuantity() {
        return originalQuantity - filledQuantity;
    }
}
