public class OrderRequest {
    private final Side side;
    private final long price;
    private final long quantity;
    private final OrderType orderType;

    // Constructor

    // every path ends here, so validation lives in exactly one place.
    public OrderRequest(Side side, long price, long quantity, OrderType orderType) {

        if (side != Side.BUY && side != Side.SELL) {
            throw new IllegalArgumentException("Select a valid Side.");
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
        if (orderType != OrderType.MARKET && price <= 0) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: Non-MARKET order type does not have a price.");
        }
        if (orderType == OrderType.MARKET && price != Order.NO_PRICE) {
            throw new IllegalArgumentException("ILLEGAL REQUEST: MARKET order type has a price.");
        }

        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.orderType = orderType;
    }

    // Static factory: Without an order type. Default to LIMIT.
    public static OrderRequest limit(Side side, long price, long quantity) {

        return new OrderRequest(side, price, quantity, OrderType.LIMIT);
    }

    // Static factory: MARKET. No price.
    public static OrderRequest market(Side side, long quantity) {
        return new OrderRequest(side, Order.NO_PRICE, quantity, OrderType.MARKET);
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
