import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {

    MatchingEngine matchingEngine = new MatchingEngine(new OrderBook());

    @Nested
    class CrossesTest {

        @Nested
        class IncomingBuyTests {

            @Test
            void incomingBuyCrossesLowerSell() { assertTrue(matchingEngine.crosses(105, 100, Side.SELL)); }

            @Test
            void incomingBuyDoesNotCrossHigherSell() {
                assertFalse(matchingEngine.crosses(105, 110, Side.SELL));
            }

            @Test
            void incomingBuyCrossesEqualSell() {
                assertTrue(matchingEngine.crosses(105, 105, Side.SELL));
            }

        }

        @Nested
        class IncomingSellTests {

            @Test
            void incomingSellIsNotCrossedByLowerBuy() {
                assertFalse(matchingEngine.crosses(105, 100, Side.BUY));
            }

            @Test
            void incomingSellIsCrossedByHigherBuy() {
                assertTrue(matchingEngine.crosses(37, 88, Side.BUY));
            }

            @Test
            void incomingSellIsCrossedByEqualBuy() {
                assertTrue(matchingEngine.crosses(15, 15, Side.BUY));
            }

        }

        @Nested
        class BoundaryValueTests {

            @Nested
            class MaxLimitTests {

                @Nested
                class BuyTests {

                    @Test
                    void incomingBuyPricedAtMaxLimitIsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(Long.MAX_VALUE, 100, Side.SELL));
                                });
                    }

                    @Test
                    void restingBuyPricedAtMaxLimitIsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(100, Long.MAX_VALUE, Side.BUY));
                                });
                    }

                }

                @Nested
                class SellTests {

                    @Test
                    void incomingSellPricedAtMaxLimitIsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(Long.MAX_VALUE, 100, Side.BUY));
                                });
                    }

                    @Test
                    void restingSellPricedAtMaxLimitIsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(100, Long.MAX_VALUE, Side.SELL));
                                });
                    }

                }

            }

            @Nested
            class MinLimitTests {

                @Test
                void incomingSellPricedAtMinLimitIsAccepted() {
                    assertTrue(matchingEngine.crosses(1, 1000, Side.BUY));
                }

                @Test
                void incomingBuyPricedAtMinLimitIsAccepted() {
                    assertTrue(matchingEngine.crosses(1, 1, Side.SELL));
                }

            }

            @Nested
            class InvalidPriceTests {

                @Nested
                class BuyTests {

                    @Test
                    void incomingBuyPricedAt0itIsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(0, 1, Side.SELL));
                                });
                    }

                    @Test
                    void incomingBuyPricedBelow0IsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(-1, 1, Side.SELL));
                                });
                    }

                }

                @Nested
                class SellTests {

                    @Test
                    void incomingSellPricedAt0IsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(0, 1, Side.BUY));
                                });
                    }

                    @Test
                    void incomingSellPricedBelow0IsRejected() {
                        assertThrows(IllegalArgumentException.class,
                                () -> {
                                    assertTrue(matchingEngine.crosses(-100, 1, Side.BUY));
                                });
                    }

                }

            }

        }
    }
}