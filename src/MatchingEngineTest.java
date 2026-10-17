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

        }
    }
}