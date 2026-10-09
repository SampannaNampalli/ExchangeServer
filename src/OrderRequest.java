public class OrderRequest {
    private final Side side;
    private final long price;
    private final long quantity;

    // Constructor
    public OrderRequest(Side side, long price, long quantity) {

        if (side != Side.BUY && side != Side.SELL) {
            throw new IllegalArgumentException("Select a valid Side.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (price == Long.MAX_VALUE) {
            throw new IllegalArgumentException("INVALID. Order priced at Long.MAX_VALUE.");
        }
        // Quantity depends on the value of a single share? So, Long.MAX_VALUE may not be the limit.
        if (quantity == Long.MAX_VALUE) {
            throw new IllegalArgumentException("INVALID. Order quantity is Long.MAX_VALUE.");
        }

        this.side = side;
        this.price = price;
        this.quantity = quantity;
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
}
