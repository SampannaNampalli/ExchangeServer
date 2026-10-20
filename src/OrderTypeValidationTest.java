import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/*

Order type and price validation, in both layers (D19):
- - OrderRequest (gateway): limit(...) -> LIMIT; market(...) -> MARKET with no price; null type -> reject;
  MARKET with a price -> reject; any other type without a real price -> reject.
- Order (real invariant): the same rules, MARKET <=> price is Order.NO_PRICE. A market order's price
  cannot be read by accident: getPrice() throws.

 */
class OrderTypeValidationTest {

    static final OrderType[] PRICED_TYPES = { OrderType.LIMIT, OrderType.IOC, OrderType.FOK, OrderType.POST_ONLY };


    @Nested
    class Request {

        @Test
        void limitFactoryMeansLimit() {
            assertEquals(OrderType.LIMIT, OrderRequest.limit(Side.BUY, 100, 10).getOrderType());
        }

        @Test
        void builtTypesAreAccepted() {
            for (OrderType type : PRICED_TYPES) {
                assertEquals(type, new OrderRequest(Side.BUY, 100, 10, type).getOrderType());
            }
            assertEquals(OrderType.MARKET, OrderRequest.market(Side.BUY, 10).getOrderType());
        }

        @Test
        void nullTypeIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new OrderRequest(Side.BUY, 100, 10, null));
        }

        @Test
        void marketFactoryCarriesNoPrice() {
            OrderRequest request = OrderRequest.market(Side.SELL, 10);

            assertEquals(Order.NO_PRICE, request.getPrice());
            assertEquals(10, request.getQuantity());
            assertEquals(Side.SELL, request.getSide());
        }

        @Test
        void marketWithAPriceIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new OrderRequest(Side.BUY, 100, 10, OrderType.MARKET));
        }

        @Test
        void pricedTypesWithoutARealPriceAreRejected() {
            for (OrderType type : PRICED_TYPES) {
                assertThrows(IllegalArgumentException.class,
                        () -> new OrderRequest(Side.BUY, Order.NO_PRICE, 10, type), type.name());
                assertThrows(IllegalArgumentException.class,
                        () -> new OrderRequest(Side.BUY, 0, 10, type), type.name());
            }
        }

        @Test
        void marketQuantityAndSideAreStillChecked() {
            assertThrows(IllegalArgumentException.class, () -> OrderRequest.market(Side.BUY, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> OrderRequest.market(Side.BUY, Order.MAX_ORDER_QUANTITY + 1));
            assertThrows(IllegalArgumentException.class, () -> OrderRequest.market(null, 10));
        }
    }

    @Nested
    class OrderConstructor {

        static Order order(long price, OrderType type) {
            return new Order(1, Side.BUY, price, 10, Instant.EPOCH, type);
        }

        @Test
        void nullTypeThrowsInsteadOfDefaultingToLimit() {
            assertThrows(IllegalArgumentException.class, () -> order(100, null));
        }

        @Test
        void typeIsKept() {
            assertEquals(OrderType.IOC, order(100, OrderType.IOC).getOrderType());
        }

        @Test
        void marketWithNoPriceIsBuilt() {
            assertEquals(OrderType.MARKET, order(Order.NO_PRICE, OrderType.MARKET).getOrderType());
        }

        @Test
        void marketWithAPriceThrows() {
            assertThrows(IllegalArgumentException.class, () -> order(100, OrderType.MARKET));
        }

        @Test
        void marketPriceCannotBeRead() {
            Order market = order(Order.NO_PRICE, OrderType.MARKET);

            assertThrows(IllegalStateException.class, market::getPrice);
        }

        @Test
        void pricedTypesWithoutARealPriceThrow() {
            for (OrderType type : PRICED_TYPES) {
                assertThrows(IllegalArgumentException.class, () -> order(Order.NO_PRICE, type), type.name());
                assertThrows(IllegalArgumentException.class, () -> order(0, type), type.name());
            }
        }
    }
}
