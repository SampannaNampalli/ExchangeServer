public class OrderRequest {
    private final Side side;
    private final long price;
    private final long quantity;
    private final OrderType orderType;

    // Constructor

    // With OrderType.
    public OrderRequest(Side side, long price, long quantity, OrderType orderType) {

        if (side != Side.BUY && side != Side.SELL) {
            throw new IllegalArgumentException("Select a valid Side.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (price > Order.MAX_PRICE) {
            throw new IllegalArgumentException("INVALID. Order priced at Long.MAX_VALUE.");
        }
        if (quantity > Order.MAX_ORDER_QUANTITY) {
            throw new IllegalArgumentException("INVALID. Order quantity is Long.MAX_VALUE.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: Order quantity must be positive.");
        }
        if (orderType == null) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: Invalid order type");
        }
        // temporary.
        if (orderType != OrderType.LIMIT && orderType != OrderType.IOC) {
            throw new IllegalArgumentException("ILLEGAL REQUEST (temporary): Only LIMIT and IOC order types are accepted for now.");
        }

        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.orderType = orderType;
    }

    // Without OrderType.
    public OrderRequest(Side side, long price, long quantity) {

        this(side, price, quantity, OrderType.LIMIT);
    }

    // Getters.

    public long getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }
}
