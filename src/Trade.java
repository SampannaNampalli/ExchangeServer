import java.util.*;
import java.time.Instant;

public class Trade {
    private final long buyOrderId;
    private final long sellOrderId;

    private final long quantity;
    private final long price;

    private final Instant timestamp;

    public Trade(long quantity, long price, long buyOrderId, long sellOrderId, Instant timestamp) {

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }

        this.quantity = quantity;
        this.price = price;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.timestamp = timestamp;
    }

    // getters.

    public long getBuyOrderId() {
        return buyOrderId;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getPrice() {
        return price;
    }

    public long getSellOrderId() {
        return sellOrderId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}

/*

Initialization

new Trade(
    buyOrderId,
    sellOrderId,
    fillQty,
    restingOrderPrice,
    Instant.now()
);


 */
