import java.util.Objects;

/*

A node of the intrusive doubly linked list inside a PriceLevel.
Book layer only: PriceLevel links and unlinks it, OrderBook holds it in its id map.

order == null  <=>  sentinel. Enforced by construction:
1. The package-private constructor rejects a null order.
2. Sentinels can only be created through sentinel().

level == this PriceLevel  <=>  the node is currently linked in that level.
level is null for sentinels and for nodes that have been unlinked.

 */
final class OrderNode {
    final Order order;
    OrderNode prev, next;
    PriceLevel level;

    // Real node. Links and level are set by PriceLevel.addOrder(), the only place that links nodes.
    OrderNode(Order order) {
        this.order = Objects.requireNonNull(order, "A real node must wrap an order.");
    }

    // Sentinel node. Private, so a null order can only come from sentinel().
    private OrderNode() {
        this.order = null;
    }

    static OrderNode sentinel() {
        return new OrderNode();
    }
}
