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

        if (order.getPriceTicks() != price) {
            throw new IllegalArgumentException("Price not matching. Insert into appropriate price-level.");
        }

        orders.add(order);
        totalVolume += order.getRemainingQuantity();
        // totalVolume is total workable/tradable volume.
        // Since it is initialization, and orders cannot move across price levels,
        // order.getOriginalQuantity() may also work.
    }

    public Order peekFirstOrder() {

        if (orders.isEmpty()) {
            return null;
        }
        if (orders.peekFirst().getRemainingQuantity() == 0) {
            throw new IllegalStateException(
                    "Fully-filled order left in queue"
            );
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

    public long getTotalVolume() {
        return this.totalVolume;
    }

    public long getPrice() {
        return this.price;
    }

    public long fillHeadOrder(long quantity) {
        Order o = orders.peekFirst();

        if (o == null) {
            throw new NoSuchElementException("There is no order at the given price");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        long quantityToFill = Math.min(
                o.getRemainingQuantity(),
                quantity
        );
        o.fill(quantityToFill);

        totalVolume -= quantityToFill;

        if (o.getRemainingQuantity() == 0) { // Maybe return the order id of the order that has been exhausted.
            orders.pollFirst();
        }

        return quantityToFill;
    }

    public boolean isFullyConsumed() {
//        return (totalVolume == 0);

        // Or
         return orders.isEmpty();

         // This is better since totalVolume is a derived state.
    }
}
