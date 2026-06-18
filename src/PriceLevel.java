import java.util.*;

public class PriceLevel {
    private final long price;
    private final Deque<Order> orders;
    private long totalVolume;

    public PriceLevel(long price) {
        this.price = price;
        orders = new ArrayDeque<>();
        totalVolume = 0;
    }

    public void addOrder(Order order) {

        if (order.getPrice() != price) {
            throw new IllegalArgumentException("Price not matching. Insert into appropriate price-level.");
        }

        orders.add(order);
        totalVolume += order.getRemainingQuantity();
        // totalVolume is total workable/tradable volume.
    }

    public Order peekFirstOrder() {

        if (orders.isEmpty()) {
            return null;
        }

        return orders.peekFirst();
    }

    public Order removeFirstOrder() {
        Order order = this.orders.pollFirst();

        if (order != null) {
            totalVolume -= order.getRemainingQuantity();
        }

        return order;
    }

    public boolean isEmpty() {
        return (this.orders.isEmpty());
    }

    public long getTotalVolume() {
        return this.totalVolume;
    }

    public long getPrice() {
        return this.price;
    }
}
