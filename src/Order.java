import java.time.Instant;

public class Order {
    private final long orderId;
    private final Side side;
    private final long price;
    private final long originalQuantity;
    private final Instant timestamp; // system timestamp.
    public static final long MAX_ORDER_QUANTITY = 1000000000L;
    public static final long MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY;
    public static final long NO_PRICE = -1;
    private long filledQuantity;
    private final OrderType orderType;

    public Order(long orderId, Side side, long price, long originalQuantity, Instant timestamp, OrderType orderType) {

        if (side != Side.BUY && side != Side.SELL) {
            throw new IllegalArgumentException("Illegal Request: Select a valid Side.");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (originalQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than 0.");
        }
        if (price > MAX_PRICE) {
            throw new IllegalArgumentException("INVALID. Order priced at Long.MAX_VALUE.");
        }
        if (originalQuantity > MAX_ORDER_QUANTITY) {
            throw new IllegalArgumentException("INVALID. Order quantity is not allowed. Try placing an order" +
                    "of smaller volume.");
        }
        if (orderType == null) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: Invalid order type");
        }
        if (orderType == OrderType.MARKET && price != Order.NO_PRICE) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: MARKET order type has a price.");
        }
        if (orderType != OrderType.MARKET && price <= 0) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: Except MARKET order type orders, all must have a valid price.");            
        }

        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.originalQuantity = originalQuantity;
        this.timestamp = timestamp;
        this.orderType = orderType;
    }

    public long getOrderId() {
        return this.orderId;
    }

    public Side getSide() {
        return this.side;
    }

    public long getPrice() {
        if (orderType == OrderType.MARKET) {
            throw new IllegalStateException("ILLEGAL REQUEST: MARKET order type orders do not have a price.");
        }
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

    public OrderType getOrderType() {
        return orderType;
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
