import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class MatchingEngineCrossesTest {

    MatchingEngine matchingEngine = new MatchingEngine(new OrderBook());

    // Incoming BUY.
//    @Test
//    void incomingBuyCrossesLowerSell() {
//        assertTrue(matchingEngine.crosses(105, 100, Side.SELL));
//    }
//
//    @Test
//    void incomingBuyDoesNotCrossHigherSell() {
//        assertFalse(matchingEngine.crosses(105, 110, Side.SELL));
//    }
//
//    @Test
//    void incomingBuyCrossesEqualSell() {
//        assertTrue(matchingEngine.crosses(105, 105, Side.SELL));
//    }

    // Incoming SELL.
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

    // Edge/Extreme Cases.
    @Test
    void incomingSellPricedAtMaxLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    assertTrue(matchingEngine.crosses(Long.MAX_VALUE, 100, Side.BUY));
                });
    }

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

    @Test
    void restingSellPricedAtMaxLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    assertTrue(matchingEngine.crosses(100, Long.MAX_VALUE, Side.SELL));
                });
    }

    @Test
    void incomingSellPricedAtMinLimitIsAccepted() {
        assertTrue(matchingEngine.crosses(1, 1000, Side.BUY));
    }

    // buy-min-limit
    @Test
    void incomingBuyPricedAtMinLimitIsAccepted() {
        assertTrue(matchingEngine.crosses(1, 1, Side.SELL));
    }

    // sell-at-0
    @Test
    void incomingSellPricedAt0IsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    assertTrue(matchingEngine.crosses(0, 1, Side.BUY));
                });
    }

    // buy-at-0
    @Test
    void incomingBuyPricedAt0itIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    assertTrue(matchingEngine.crosses(0, 1, Side.SELL));
                });
    }

    // sell-invalid-negative-limit
    @Test
    void incomingSellPricedBelow0IsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    assertTrue(matchingEngine.crosses(-100, 1, Side.BUY));
                });
    }

    // buy-invalid-negative-limit
    @Test
    void incomingBuyPricedBelow0IsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    assertTrue(matchingEngine.crosses(-1, 1, Side.SELL));
                });
    }

}