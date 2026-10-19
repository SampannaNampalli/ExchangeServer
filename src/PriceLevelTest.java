import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PriceLevelTest {

    static final long PRICE = 100;

    PriceLevel level;
    Order a, b, c;
    OrderNode nodeA, nodeB, nodeC;

    // Level at 100: A(10) <-> B(20) <-> C(30). totalVolume = 60, noOfOrders = 3.
    @BeforeEach
    void setUp() {
        level = new PriceLevel(PRICE);
        a = buy(1, 10);
        b = buy(2, 20);
        c = buy(3, 30);
        nodeA = level.addOrder(a);
        nodeB = level.addOrder(b);
        nodeC = level.addOrder(c);
    }

    static Order buy(long id, long quantity) {
        return new Order(id, Side.BUY, PRICE, quantity, Instant.EPOCH, OrderType.LIMIT);
    }

    static List<Long> ids(List<Order> orders) {
        return orders.stream().map(Order::getOrderId).collect(Collectors.toList());
    }

    // Checks every invariant by walking the list independently of the counters.
    static void assertInvariants(PriceLevel level) {
        List<Order> forward = level.ordersHeadToTail();
        List<Order> backward = new ArrayList<>(level.ordersTailToHead());
        Collections.reverse(backward);
        assertEquals(forward, backward, "prev pointers disagree with next pointers");

        long volume = 0;
        for (Order o : forward) {
            volume += o.getRemainingQuantity();
        }
        assertEquals(volume, level.getTotalVolume(), "totalVolume drifted");
        assertEquals(forward.size(), level.getNoOfOrders(), "noOfOrders drifted");
        assertEquals(forward.isEmpty(), level.isFullyConsumed());
    }

    static void assertCleared(OrderNode node) {
        assertNull(node.prev);
        assertNull(node.next);
        assertNull(node.level);
    }

    @Nested
    class Empty {

        @Test
        void newLevelIsEmpty() {
            PriceLevel empty = new PriceLevel(PRICE);
            assertTrue(empty.isFullyConsumed());
            assertNull(empty.peekFirstOrder());
            assertEquals(0, empty.getTotalVolume());
            assertEquals(0, empty.getNoOfOrders());
            assertInvariants(empty);
        }

        @Test
        void fillOnEmptyLevelThrows() {
            PriceLevel empty = new PriceLevel(PRICE);
            assertThrows(NoSuchElementException.class, () -> empty.fillHeadOrder(5));
        }
    }

    @Nested
    class AddOrder {

        @Test
        void addsInTimePriority() {
            assertEquals(List.of(1L, 2L, 3L), ids(level.ordersHeadToTail()));
            assertEquals(60, level.getTotalVolume());
            assertEquals(3, level.getNoOfOrders());
            assertSame(a, level.peekFirstOrder());
            assertInvariants(level);
        }

        @Test
        void returnedNodeIsLinkedInThisLevel() {
            assertSame(level, nodeB.level);
            assertSame(nodeA, nodeB.prev);
            assertSame(nodeC, nodeB.next);
            assertSame(b, nodeB.order);
        }

        @Test
        void partiallyFilledOrderAddsRemainingQuantity() {
            Order d = buy(4, 100);
            d.fill(30);
            level.addOrder(d);

            assertEquals(60 + 70, level.getTotalVolume());
            assertInvariants(level);
        }

        @Test
        void wrongPriceIsRejectedAndLevelUntouched() {
            Order wrong = new Order(4, Side.BUY, PRICE + 1, 5, Instant.EPOCH, OrderType.LIMIT);
            assertThrows(IllegalArgumentException.class, () -> level.addOrder(wrong));

            assertEquals(List.of(1L, 2L, 3L), ids(level.ordersHeadToTail()));
            assertInvariants(level);
        }

        @Test
        void zeroRemainingIsRejectedAndLevelUntouched() {
            Order filled = buy(4, 5);
            filled.fill(5);
            assertThrows(IllegalArgumentException.class, () -> level.addOrder(filled));

            assertEquals(List.of(1L, 2L, 3L), ids(level.ordersHeadToTail()));
            assertInvariants(level);
        }

        @Test
        void nullOrderIsRejected() {
            assertThrows(NullPointerException.class, () -> new OrderNode(null));
        }
    }

    @Nested
    class Unlink {

        @Test
        void unlinkHead() {
            level.unlink(nodeA);

            assertEquals(List.of(2L, 3L), ids(level.ordersHeadToTail()));
            assertEquals(50, level.getTotalVolume());
            assertEquals(2, level.getNoOfOrders());
            assertSame(b, level.peekFirstOrder());
            assertCleared(nodeA);
            assertInvariants(level);
        }

        @Test
        void unlinkMiddle() {
            level.unlink(nodeB);

            assertEquals(List.of(1L, 3L), ids(level.ordersHeadToTail()));
            assertEquals(40, level.getTotalVolume());
            assertEquals(2, level.getNoOfOrders());
            assertCleared(nodeB);
            assertInvariants(level);
        }

        @Test
        void unlinkTail() {
            level.unlink(nodeC);

            assertEquals(List.of(1L, 2L), ids(level.ordersHeadToTail()));
            assertEquals(30, level.getTotalVolume());
            assertEquals(2, level.getNoOfOrders());
            assertCleared(nodeC);
            assertInvariants(level);
        }

        @Test
        void unlinkOnlyNodeEmptiesLevel() {
            PriceLevel single = new PriceLevel(PRICE);
            OrderNode only = single.addOrder(buy(9, 10));

            single.unlink(only);

            assertTrue(single.isFullyConsumed());
            assertNull(single.peekFirstOrder());
            assertEquals(0, single.getTotalVolume());
            assertEquals(0, single.getNoOfOrders());
            assertCleared(only);
            assertInvariants(single);
        }

        @Test
        void unlinkAllInAnyOrderEmptiesLevel() {
            level.unlink(nodeB);
            level.unlink(nodeC);
            level.unlink(nodeA);

            assertTrue(level.isFullyConsumed());
            assertEquals(0, level.getTotalVolume());
            assertInvariants(level);
        }

        @Test
        void addAfterUnlinkGoesToBack() {
            level.unlink(nodeA);
            level.addOrder(buy(4, 5));

            assertEquals(List.of(2L, 3L, 4L), ids(level.ordersHeadToTail()));
            assertInvariants(level);
        }

        @Test
        void cancelOfPartiallyFilledOrderSubtractsRemaining() {
            // A (10) is filled 4 at the head, then cancelled with 6 still live.
            level.fillHeadOrder(4);
            assertEquals(56, level.getTotalVolume());

            level.unlink(nodeA);

            assertEquals(50, level.getTotalVolume());
            assertEquals(List.of(2L, 3L), ids(level.ordersHeadToTail()));
            assertInvariants(level);
        }

        @Test
        void doubleUnlinkThrowsAndLevelUntouched() {
            level.unlink(nodeB);
            assertThrows(IllegalStateException.class, () -> level.unlink(nodeB));

            assertEquals(List.of(1L, 3L), ids(level.ordersHeadToTail()));
            assertInvariants(level);
        }

        @Test
        void sentinelUnlinkThrows() {
            assertThrows(IllegalStateException.class, () -> level.unlink(OrderNode.sentinel()));
            assertInvariants(level);
        }

        @Test
        void foreignNodeThrowsAndBothLevelsUntouched() {
            PriceLevel other = new PriceLevel(PRICE + 1);
            OrderNode foreign = other.addOrder(new Order(9, Side.BUY, PRICE + 1, 5, Instant.EPOCH, OrderType.LIMIT));

            assertThrows(IllegalStateException.class, () -> level.unlink(foreign));

            assertEquals(List.of(1L, 2L, 3L), ids(level.ordersHeadToTail()));
            assertEquals(List.of(9L), ids(other.ordersHeadToTail()));
            assertSame(other, foreign.level);
            assertInvariants(level);
            assertInvariants(other);
        }
    }

    @Nested
    class FillHeadOrder {

        @Test
        void partialFillReducesHeadAndVolume() {
            long filled = level.fillHeadOrder(4);

            assertEquals(4, filled);
            assertEquals(6, a.getRemainingQuantity());
            assertEquals(56, level.getTotalVolume());
            assertSame(a, level.peekFirstOrder());
            assertInvariants(level);
        }

        @Test
        void fillLargerThanHeadFillsOnlyHead() {
            long filled = level.fillHeadOrder(25);

            assertEquals(10, filled);
            assertEquals(0, a.getRemainingQuantity());
            assertEquals(20, b.getRemainingQuantity());
            assertEquals(50, level.getTotalVolume());
        }

        @Test
        void fullFillLeavesExhaustedHeadLinkedUntilUnlinked() {
            level.fillHeadOrder(10);

            // Window between the fill and OrderBook.removeOrder().
            assertEquals(List.of(1L, 2L, 3L), ids(level.ordersHeadToTail()));
            assertEquals(3, level.getNoOfOrders());
            assertFalse(level.isFullyConsumed());
            assertInvariants(level);
        }

        @Test
        void peekThrowsOnExhaustedHead() {
            level.fillHeadOrder(10);
            assertThrows(IllegalStateException.class, () -> level.peekFirstOrder());
        }

        @Test
        void fillThrowsOnExhaustedHead() {
            level.fillHeadOrder(10);
            assertThrows(IllegalStateException.class, () -> level.fillHeadOrder(5));
            assertEquals(50, level.getTotalVolume());
        }

        @Test
        void unlinkOfFilledHeadSubtractsZero() {
            level.fillHeadOrder(10);
            level.unlink(nodeA);

            assertEquals(50, level.getTotalVolume());
            assertSame(b, level.peekFirstOrder());
            assertInvariants(level);
        }

        @Test
        void fullFillOfOnlyOrderThenUnlinkEmptiesLevel() {
            PriceLevel single = new PriceLevel(PRICE);
            OrderNode only = single.addOrder(buy(9, 10));

            single.fillHeadOrder(10);
            assertEquals(0, single.getTotalVolume());
            assertFalse(single.isFullyConsumed());   // Volume 0, but the node is still linked.

            single.unlink(only);
            assertTrue(single.isFullyConsumed());
            assertInvariants(single);
        }

        @Test
        void nonPositiveQuantityThrows() {
            assertThrows(IllegalArgumentException.class, () -> level.fillHeadOrder(0));
            assertThrows(IllegalArgumentException.class, () -> level.fillHeadOrder(-1));
            assertEquals(60, level.getTotalVolume());
        }
    }
}
