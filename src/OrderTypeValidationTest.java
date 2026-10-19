import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/*

Order type validation, in both layers (D19):
- OrderRequest (gateway): no type given -> LIMIT; null -> reject; a type not built yet -> reject.
- Order (real invariant): null -> throw (our bug, not a client choice); a type not built yet -> throw.

 */
class OrderTypeValidationTest {

    static final OrderType[] NOT_BUILT_YET = { OrderType.FOK, OrderType.MARKET, OrderType.POST_ONLY };

    @Nested
    class Request {

        @Test
        void noTypeMeansLimit() {
            assertEquals(OrderType.LIMIT, new OrderRequest(Side.BUY, 100, 10).getOrderType());
        }

        @Test
        void limitAndIocAreAccepted() {
            assertEquals(OrderType.LIMIT, new OrderRequest(Side.BUY, 100, 10, OrderType.LIMIT).getOrderType());
            assertEquals(OrderType.IOC, new OrderRequest(Side.BUY, 100, 10, OrderType.IOC).getOrderType());
        }

        @Test
        void nullTypeIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new OrderRequest(Side.BUY, 100, 10, null));
        }

        @Test
        void typesNotBuiltYetAreRejected() {
            for (OrderType type : NOT_BUILT_YET) {
                assertThrows(IllegalArgumentException.class,
                        () -> new OrderRequest(Side.BUY, 100, 10, type), type.name());
            }
        }
    }

    @Nested
    class OrderConstructor {

        @Test
        void nullTypeThrowsInsteadOfDefaultingToLimit() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Order(1, Side.BUY, 100, 10, Instant.EPOCH, null));
        }

        @Test
        void typesNotBuiltYetThrow() {
            for (OrderType type : NOT_BUILT_YET) {
                assertThrows(IllegalArgumentException.class,
                        () -> new Order(1, Side.BUY, 100, 10, Instant.EPOCH, type), type.name());
            }
        }

        @Test
        void typeIsKept() {
            assertEquals(OrderType.IOC, new Order(1, Side.BUY, 100, 10, Instant.EPOCH, OrderType.IOC).getOrderType());
        }
    }
}
