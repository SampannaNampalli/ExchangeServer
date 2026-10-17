import java.util.*;

/*

Orders resting at one price, in time priority, stored as an intrusive doubly linked list
with sentinel head and tail:

    head <-> o1 <-> o2 <-> ... <-> tail

Every linked node always has a non-null prev and next (at worst a sentinel), so linking and
unlinking need no head / tail / only-node branches. head.prev and tail.next stay null: nothing
ever walks past a sentinel.

Invariants:
1. totalVolume == sum of getRemainingQuantity() over the linked nodes.
2. noOfOrders  == number of linked nodes.
3. node.level == this  <=>  node is linked in this level.
4. Every linked order has remaining quantity > 0, except the head in the window between
   fillHeadOrder() exhausting it and OrderBook.removeOrder() unlinking it.

*/

public class PriceLevel {
    private final long price;
    private final OrderNode head;
    private final OrderNode tail;
    private long totalVolume;
    private long noOfOrders;

    public PriceLevel(long price) {
        this.price = price;
        this.head = OrderNode.sentinel();
        this.tail = OrderNode.sentinel();
        head.next = tail;
        tail.prev = head;
        totalVolume = 0;
        noOfOrders = 0;
    }

    // Returns the node so OrderBook can keep it in its id map for O(1) cancel.
    OrderNode addOrder(Order order) {

        // All checks before any mutation: a throw must leave the level untouched.
        if (order.getPrice() != price) {
            throw new IllegalArgumentException("Price not matching. Insert into appropriate price-level.");
        }
        if (order.getRemainingQuantity() <= 0) {
            throw new IllegalArgumentException("Cannot add an order with zero remaining quantity.");
        }
        if (order.getRemainingQuantity() > (Long.MAX_VALUE - totalVolume)) {
            throw new IllegalStateException("Volume of price-level " + price + " exceeds allowed limit.");
        }

        OrderNode node = new OrderNode(order);
        OrderNode last = tail.prev;   // Read before tail.prev is overwritten below.

        node.prev = last;
        node.next = tail;
        last.next = node;
        tail.prev = node;
        node.level = this;

        totalVolume += order.getRemainingQuantity();
        noOfOrders++;

        return node;
    }

    // Returns null if the level is empty.
    public Order peekFirstOrder() {

        OrderNode first = head.next;
        if (first == tail) {
            return null;
        }
        if (first.order.getRemainingQuantity() == 0) {
            throw new IllegalStateException(
                    "Fully-filled order left in queue"
            );
        }

        return first.order;
    }

    // Fills the head order only. Removing an exhausted head is OrderBook.removeOrder()'s job, so that
    // the id map and the list change together.
    public long fillHeadOrder(long quantity) {

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Goes through peekFirstOrder() so an exhausted head that was never removed fails with a clear message.
        Order o = peekFirstOrder();
        if (o == null) {
            throw new NoSuchElementException("There is no order at the given price");
        }

        long quantityToFill = Math.min(
                o.getRemainingQuantity(),
                quantity
        );

        o.fill(quantityToFill);
        totalVolume -= quantityToFill;

        return quantityToFill;
    }

    // Used for both fill-removal (remaining 0) and cancel (remaining > 0).
    void unlink(OrderNode node) {

        // Guards before touching any pointer.
        if (node.order == null) {
            throw new IllegalStateException("Cannot unlink a sentinel node.");
        }
        if (node.level != this) {
            throw new IllegalStateException("Order " + node.order.getOrderId() + " is not linked in price-level "
                    + price + " (already unlinked, or belongs to another level).");
        }

        node.prev.next = node.next;
        node.next.prev = node.prev;

        // 0 for a filled order (fillHeadOrder already subtracted it), the live remainder for a cancel.
        totalVolume -= node.order.getRemainingQuantity();
        noOfOrders--;

        // A stale node now fails the level guard instead of corrupting a list.
        node.prev = null;
        node.next = null;
        node.level = null;
    }

    public boolean isFullyConsumed() {
        return head.next == tail;
    }

    public long getTotalVolume() {
        return this.totalVolume;
    }

    public long getPrice() {
        return this.price;
    }

    public long getNoOfOrders() {
        return noOfOrders;
    }

    // For tests and debugging only. O(n) copy in time priority; exposes state, interprets nothing.
    List<Order> ordersHeadToTail() {
        List<Order> result = new ArrayList<>();
        for (OrderNode n = head.next; n != tail; n = n.next) {
            result.add(n.order);
        }
        return result;
    }

    // For tests only. Walks the prev pointers, so tests can check them against the next pointers.
    List<Order> ordersTailToHead() {
        List<Order> result = new ArrayList<>();
        for (OrderNode n = tail.prev; n != head; n = n.prev) {
            result.add(n.order);
        }
        return result;
    }
}
