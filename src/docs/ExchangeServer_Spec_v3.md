# Exchange Server — Project Spec & Progress Log (v3)

> Continuation of `ExchangeServer_Spec_v2.md`. This version folds in the finalized `match()` / `submit()` design, the resting-terminology rename, and every previously-flagged bug fix. See the companion `ExchangeServer_Vision.md` for project motivation and working style — upload both together to resume with full context.

## Goal
Build a Java stock exchange / matching engine server, starting with a single-threaded, correct price-time-priority matching core. Later phases: concurrency, JUnit 5 tests, persistence, observability, load-testing (req/s). (Full long-term roadmap now lives in `ExchangeServer_Vision.md`.)

## Current File Structure
```
ExchangeServer
    Side.java
    Order.java            <-- constructor validation fixed this session
    Trade.java
    PriceLevel.java
    OrderBook.java         <-- removePriceLevel() fixed, bestLevel() helper added
    MatchingEngine.java    <-- crosses(), match(), submit() all finalized; JUnit tests next
    Main.java              <-- not yet written
```

## Terminology Change (this session)
Renamed `oppositeSide` / `oppositePL` / `oppositeLevel` → **`restingSide` / `restingPL` / `restingLevel`** throughout `MatchingEngine`, to match real exchange terminology — the side already resting in the book, as distinct from the incoming order.

## Known Bugs From v2 — ALL FIXED THIS SESSION
1. **`OrderBook.removePriceLevel()`** — was checking the wrong map and using `.firstEntry()` incorrectly with no null-guard. Fixed (see below).
2. **`Order` constructor allowed zero price/qty** — fixed, all three fields now validated.
3. **`orderId` off-by-one** — constructor used `< 0` (message said "must be positive" but `0` slipped through). Fixed to `<= 0`, matching order IDs starting at 1.

## `MatchingEngine.java` — Finalized Methods

### `crosses()` — finalized (delegate signature updated from v2)
Delegate now takes a `PriceLevel` directly instead of reaching into `orderBook` itself — this removes a redundant book-fetch (see `submit()` below).

```java
boolean crosses(Order incomingOrder, PriceLevel restingPL) {
    Side restingSide = (incomingOrder.getSide() == Side.BUY) ? Side.SELL : Side.BUY;
    return crosses(incomingOrder.getPrice(), restingPL.getPrice(), restingSide);
}

boolean crosses(long incomingPrice, long restingPrice, Side restingSide) {
    if (restingSide == Side.BUY) {
        return (incomingPrice <= restingPrice);
    } else {
        return (incomingPrice >= restingPrice);
    }
}
```
**Open item, not yet written:** agreed to add an input-validation guard to the core `(long, long, Side)` overload, rejecting non-positive prices — same invariant `Order`'s constructor now enforces upstream. Not yet implemented.

### `match()` — finalized, written and reviewed this session
```java
Trade match(Order incomingOrder, PriceLevel restingPL) {
    Order restingOrder = restingPL.peekFirstOrder(); // must happen BEFORE fillHeadOrder() — see note

    long buyOrderId, sellOrderId;
    if (incomingOrder.getSide() == Side.BUY) {
        buyOrderId = incomingOrder.getOrderId();
        sellOrderId = restingOrder.getOrderId();
    } else {
        buyOrderId = restingOrder.getOrderId();
        sellOrderId = incomingOrder.getOrderId();
    }

    long quantity = restingPL.fillHeadOrder(incomingOrder.getRemainingQuantity());
    incomingOrder.fill(quantity);

    return new Trade(quantity, restingPL.getPrice(), buyOrderId, sellOrderId, Instant.now());
}
```
Design notes:
- `peekFirstOrder()` must be captured **before** `fillHeadOrder()` — `fillHeadOrder()` internally calls `pollFirst()` if the resting order is fully consumed, which would lose the reference/ID needed for the `Trade` if called first.
- Bug found & fixed: buy/sell ID assignment must branch on `incomingOrder.getSide()` — the initial draft hardcoded incoming-as-buy, which is wrong when the incoming order is a SELL.
- Trade quantity is trusted directly from `fillHeadOrder()`'s return value (the `min()` is already computed at the source) rather than recomputed separately in `match()` — keeps invariant-guarding logic in one place.
- `match()` does **not** call `removePriceLevel()` — drained-level cleanup is `submit()`'s job, keeping `match()` scoped to "fill both sides + build the `Trade`" only.

### `submit()` — finalized, single-fetch optimized
```java
List<Trade> submit(Order incomingOrder) {
    List<Trade> trades = new ArrayList<>();
    Side restingSide = (incomingOrder.getSide() == Side.BUY) ? Side.SELL : Side.BUY;

    Optional<PriceLevel> restingLevelOpt;
    while (incomingOrder.getRemainingQuantity() > 0
            && (restingLevelOpt = orderBook.bestLevel(restingSide)).isPresent()
            && crosses(incomingOrder, restingLevelOpt.get())) {
        Trade trade = match(incomingOrder, restingLevelOpt.get());
        trades.add(trade);

        if (restingLevelOpt.get().isFullyConsumed()) {
            orderBook.removePriceLevel(restingSide);
        }
    }

    if (incomingOrder.getRemainingQuantity() > 0) {
        orderBook.add(incomingOrder);
    }

    return trades;
}
```
Design notes:
- Single book-fetch per iteration (`orderBook.bestLevel(restingSide)`), reused across the emptiness check, `crosses()`, and `match()` — resolves the triple-fetch redundancy flagged this session (v2 only flagged a *double*-unwrap of `Optional`, not the full three-call redundancy).
- Verified safe: nothing mutates the book mid-iteration before all three uses of `restingLevelOpt` complete within that same iteration.
- Verified: nothing after the loop exits — via full-fill, empty-book, or no-longer-crosses — reads `restingLevelOpt`. Its scope needing to live outside the `while` is a Java syntax requirement only, not a semantic dependency.
- The standalone `oppositeSideIsEmpty()` method was dropped — absorbed into the inline `Optional.isPresent()` check in the loop condition.

## `OrderBook.java` — Updated

New helper method:
```java
public Optional<PriceLevel> bestLevel(Side side) {
    return (side == Side.BUY) ? (bestBid()) : (bestAsk());
}
```

`removePriceLevel()` — fixed, signature simplified from `(Side, long price)` to `(Side)` only, matching "remove current best level of this side," the only real call site:
```java
public void removePriceLevel(Side side) {
    PriceLevel pl;
    if (side == Side.BUY) {
        if (bids.isEmpty()) {
            throw new IllegalStateException("The price-level is empty.");
        }
        pl = bids.firstEntry().getValue();
        if (!pl.isFullyConsumed()) {
            throw new IllegalStateException("Price-level is not empty.");
        }
        bids.remove(pl.getPrice());
    } else {
        if (asks.isEmpty()) {
            throw new IllegalStateException("The price-level is empty.");
        }
        pl = asks.firstEntry().getValue();
        if (!pl.isFullyConsumed()) {
            throw new IllegalStateException("Price Level is not empty.");
        }
        asks.remove(pl.getPrice());
    }
}
```
Fixed from v2's known bug: was checking the wrong map, used `.firstEntry()` incorrectly, and threw `IllegalAccessError` (an `Error`, not an `Exception` — wrong category entirely). Now: correct map per side, `.firstEntry()` is intentional given the simplified "current best" semantics, `isFullyConsumed()` guard added, `IllegalStateException` used consistently for both guards. The empty-map guard is kept deliberately as defensive protection, even though currently unreachable given how `submit()` calls it.

## `Order.java` — Constructor Fixed
```java
public Order(long orderId, Side side, long priceTicks, long originalQuantity, Instant timestamp) {
    if (orderId <= 0) {
        throw new IllegalArgumentException("Order ID must be positive");
    }
    if (priceTicks <= 0) {
        throw new IllegalArgumentException("Price must be positive");
    }
    if (originalQuantity <= 0) {
        throw new IllegalArgumentException("Original quantity must be positive");
    }
    this.orderId = orderId;
    this.side = side;
    this.priceTicks = priceTicks;
    this.originalQuantity = originalQuantity;
    this.timestamp = timestamp;
}
```
Fixed from v2: was `orderId < 0`, which let `0` through despite the message claiming "must be positive." Corrected to `<= 0`, matching order IDs starting at 1 in `MatchingEngine`.

## `MatchingEngine.createOrder()` — Updated
Return type simplified from `Optional<List<Trade>>` to `List<Trade>` — the `Optional` was never actually empty (`submit()` always returns a real, possibly-empty list), so the wrapping added no value.

Also now validates against zero-value `price`/`quantity` directly in `createOrder()`, throwing `IllegalArgumentException` on either.

**Open question, not yet resolved:** `createOrder()` validates price/quantity, and `Order`'s constructor now independently validates the same thing. Worth revisiting against the "invariants protected at source, not re-checked redundantly" principle — is `createOrder()`'s check now redundant, or does it serve a distinct purpose (fail-fast before object construction, a clearer error message at the API boundary)?

## CURRENT OPEN TASK — where this session left off

**Writing JUnit 5 tests for `crosses()`.**

Design agreed, not yet written:
- **6 logical cases** for the pure core `crosses(long, long, Side)`: 2 values of `restingSide` (BUY, SELL) × 3 price relations (incoming > resting, incoming < resting, incoming = resting) — confirmed exhaustive, since these are the method's only two branching dimensions.
- **Boundary-value tests** — e.g. extreme values like `Long.MAX_VALUE`, and the smallest legal price `1L` now that `Order`'s constructor rejects `<= 0`.
- **A test for the input-validation guard** being added to `crosses(long, long, Side)` itself (guard not yet written — see open item above).

**Resume here:** add the validation guard to the core `crosses()` overload, then write the JUnit test class covering all three groups above.

## Not Yet Started
- `Main.java`
- Spring Boot migration — deferred until JUnit tests + `Main.java` exist. See `ExchangeServer_Vision.md` for the architecture direction already discussed (microservices at the edges, single-threaded matching core per instrument in the middle).
- Concurrency, persistence, distributed systems, DevOps phases — all tracked in `ExchangeServer_Vision.md`, not here.

## Working Style Notes (for continuity)
- Guided/Socratic teaching style — question-driven, work through the "why" before the "how," confirm understanding at each step before moving on.
- Prefers concrete small examples/walkthroughs before generalizing to pseudocode/code.
- Wants design rationale made explicit, but should be told when a justification is being over-reached (e.g. don't over-attribute every choice to "this is how production systems do it" when "easier to test/reason about" is the real and sufficient reason).
- User writes code themselves and asks for review/bug-spotting rather than having code written for them.
- Full detail on working style and long-term goals now lives in the companion `ExchangeServer_Vision.md` — this section can stay short in future spec versions.
