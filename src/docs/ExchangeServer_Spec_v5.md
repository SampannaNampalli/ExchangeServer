# ExchangeServer — Spec v5

**Supersedes:** Spec v3 and Spec v4. Self-contained: everything still valid from v3 is folded in below.
**Companion:** `ExchangeServer_Vision_v3.md` (working style, principles, roadmap — the roadmap is NOT repeated here).
**Scope:** O(1) cancel design, book-layer ownership of order lifecycle, input-log/replay, determinism. Design is settled; step 1 is in progress (code is mid-conversion and does not compile, see §1).
**Verified against the local working tree on 2026-10-14** (including uncommitted changes).

---

## 1. Where we are

**Implemented**
- Domain model: `Side`, `Order`, `Trade`, `PriceLevel`, `OrderBook`
- `MatchingEngine`: `submit()` overloads -> `process(Order)`, `match()`, `crosses()`; price-time priority; trades execute at the resting order's price
- `Order.MAX_ORDER_QUANTITY = 1_000_000_000L` (private, enforced in the `Order` constructor); level volume overflow guard in `PriceLevel.addOrder()`
- `OrderRequest` as API entry point; `createOrder(OrderRequest)` delegates to `createOrder(Side, long, long)` (duplicated validation removed)
- `crosses(long, long, Side)` guard: rejects non-positive and `Long.MAX_VALUE` prices
- `fillHeadOrder()` no longer removes the head (D3 done)
- JUnit 5: per-method test classes (`MatchingEngineCrossesTest`, `...MatchTest`, `...SubmitTest`) plus `MatchingEngineTest` with `@Nested` grouping. `MatchingEngineMatchTest` (lines ~189, 204, 255) asserts that `match()` removes the filled head; these fail until step 4.
- Terminology: **resting** (`restingSide`, `restingPL`, `restingLevel`) = already in the book, vs the incoming order

**Current code facts that drive this spec**
- `Order`: final `orderId`, `side`, `price` (not `priceTicks`), `originalQuantity`, `timestamp` (passed in from outside); mutable `filledQuantity`. Constructor rejects `<= 0` for id, price, quantity, `price > MAX_PRICE` and quantity `> MAX_ORDER_QUANTITY` (D20). `fill(q)` rejects non-positive and overfill.
- `PriceLevel` (**mid-conversion, does not compile**): `OrderNode` exists (`order`, `prev`, `next`, `priceLevel`; 3-arg constructor), but `PriceLevel` still stores nodes in a `java.util.LinkedList<OrderNode>`. Problems: `addOrder` calls a 2-arg constructor and `getLast()` throws on an empty list; `unlinkOrder` ignores its argument (removes the two head entries) and refuses to unlink the only node; `fillHeadOrder` NPEs on an empty level before its null check; `noOfOrders` is derived from `orders.size()`. Being replaced in step 1 (see §5).
- `OrderBook`: bids `TreeMap` (reverse comparator), asks `TreeMap`; `add()`, `bestBid/Ask`, `bestLevel(Side)`, `getVolume`, `removePriceLevel(Side)` (**removes only the best level**, throws `IllegalStateException` if empty or not fully consumed). No id map yet.
- `MatchingEngine.match(incoming, restingPL)`: peeks resting head, calls `fillHeadOrder`, fills incoming, builds `Trade` with `Instant.now()`. `process()` still has the `removePriceLevel(restingSide)` block. **Engine is broken until step 4:** since `fillHeadOrder` no longer removes, a drained level is never "fully consumed", and the next iteration's `peekFirstOrder()` throws on the zero-remaining head. `OrderBook` is an instance field of `MatchingEngine`.
- `createOrder()` returns an `Order`; order ids start at 1.

---

## 2. Decisions (settled)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **`OrderBook` owns the `orderId -> OrderNode` map.** It is the single place an order's life on the book ends. | The invariant "id in map iff order rests in a level" can only be kept by the code that also mutates levels. `MatchingEngine` must not touch the map. |
| D2 | **One `unlink(node)` in `PriceLevel`** serves fill-removal and cancel. | Removing the head is a special case of unlinking; one code path, one place for invariants. |
| D3 | **`fillHeadOrder()` only fills** (peek, fill, subtract from `totalVolume`, return quantity). It no longer removes. | Otherwise `OrderBook` never learns of the removal and the map goes stale. |
| D4 | **After a fill, `match()` calls `book.removeOrder(...)` if the resting order has 0 remaining.** `match()` signature unchanged. | `OrderBook` is already a field of `MatchingEngine`. |
| D5 | **`OrderNode` wrapper** (not back-references in `Order`); the map stores `OrderNode`. | Keeps list machinery in the book layer; `Order` stays a clean domain object. Only `PriceLevel` and `OrderBook` ever hold nodes (`peekFirstOrder()` returns the `Order`), so the engine cannot reach the links. Cost: one extra object (~32 B) and pointer hop per resting order. **Revisit at step 6 only if benchmarks show the node hop or allocation matters; try node pooling before making `Order` intrusive.** The choice is cheap to reverse because `OrderNode` does not leak outside the book layer. |
| D6 | **Self-implemented doubly linked list with caller-held node handles** (`OrderNode`) in `PriceLevel`. Intrusive in effect, not in `Order` itself. | `java.util.LinkedList` hides its nodes, so no O(1) removal of a node you hold; a `ListIterator` handle is invalidated by any other modification, so it cannot be stored in a map. |
| D7 | **Sentinel head/tail nodes.** | `unlink` is branch-free for head/tail/only-node cases. |
| D8 | **`unlink` nulls the node's `prev`, `next`, `level`.** | No dangling pointers; stale nodes are detectable. |
| D9 | **`unlink` rejects** (a) nodes with `order == null` (sentinels/corruption), (b) nodes whose `level != this` (stale or foreign); throws `IllegalStateException`. | **Revised rationale:** with sentinels, a cleared node has null `prev/next`, so an unguarded unlink would NPE with no useful message. A node belonging to *another* level would silently corrupt both lists. The explicit checks give a clear failure. (The v4 reason "looks like the only node, wipes head/tail" only applied to a null-terminated list and no longer holds.) |
| D10 | **Eager removal of emptied price levels.** The book never contains empty levels. | Strict invariant, simple `bestBid/bestAsk`. Cost: O(log L) `TreeMap.remove` on any cancel **or fill** that empties a level. |
| D11 | **Cached best-level pointer deferred to benchmarking.** | `firstEntry()` is O(log L); L is small; measure first. |
| D12 | **Log source of truth = sequenced input commands**, replayed through the real engine. No separate replay methods. | Live and replay paths cannot drift. Requires a deterministic engine. |
| D13 | **Rejected commands that depend on engine state stay in the log.** | e.g. cancel of unknown id depends on book state. |
| D14 | **Cleanup responsibility moved (supersedes v3).** Drained-level removal is done by `OrderBook.removeOrder`, not by `submit()`. `match()` now fills both sides, builds the `Trade`, and requests removal of a fully filled resting order. | Follows from D1, D3, D4, D10. |
| D15 | **A duplicate order id in `add()` throws** (`IllegalStateException`). | Ids are assigned by our own code (engine today, sequencer later), so a duplicate means our code is broken: an internal invariant violation, not an external reject (principle 6). |
| D16 | **Order ids are never reused**, even after fill or cancel. Assigned by a monotonically increasing counter. | Anything that refers to an id would become ambiguous: a logged `cancel(id)`, trade history, client acks. Replay itself would still be deterministic; the references are what break. |
| D17 | **`cancel()` returns `CancelResult(CancelStatus status, long cancelledQuantity)`** with `enum CancelStatus { CANCELLED, UNKNOWN_ORDER }`. `UNKNOWN_ORDER` = the id is not resting (never existed, fully filled, or already cancelled; the book cannot and need not tell these apart). `cancelledQuantity` = remaining quantity at the moment of cancel (0 for a reject). | The client needs the reason for a reject and, on success, how much was actually cancelled vs already filled, to reconcile its position. An enum (not a `String` message, not an exception) is something callers can branch on. `NOT_OWNER` deferred until `Order` has a participant id (see §6). |
| D18 | **Level removal is private to `removeOrder`.** `removePriceLevel(Side)` is deleted in step 4 (it has no callers once `process()` stops calling it, and with eager removal the best level is never empty, so it would always throw). The private removal checks that `TreeMap.remove(price)` returned the very level that was just emptied, and throws otherwise. | One owner for level removal (principle 2). The return-value check turns a wrong-side / wrong-key bug from silent corruption into a loud failure. |
| D19 | **Validation lives in exactly two layers.** `OrderRequest` = gateway-side stateless check (fail fast at the boundary; becomes a reject message at the TCP gateway). `Order` constructor = the real invariant (an invalid `Order` is impossible no matter who builds it: tests, replay). `createOrder` has no checks; the bypass `submit(Side, long, long)` was deleted. `crosses()` has no guard: its inputs are always prices of valid `Order`s. | Middle-layer copies added nothing and drifted apart (quantity limit was `Long.MAX_VALUE` in two layers and 1e9 in the third). Stateless rejects never enter the log (§4). The reason is the log and clear rejects, not latency. |
| D20 | **Limits are constants in `Order`, the single source:** `MAX_ORDER_QUANTITY = 1_000_000_000`, `MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY` (= 9,223,372,036 ticks). Both limits inclusive: `1 <= price <= MAX_PRICE`, `1 <= quantity <= MAX_ORDER_QUANTITY`. `OrderRequest` references them. | `MAX_PRICE` is derived so that notional `price × quantity` can never overflow a `long` (needed by market data / risk / trade value). Code constants, not a config file: the limits are engine behaviour, and a config change between a live run and its replay would turn logged commands into poison pills. If ever moved to config, the value must be logged with the run. |

---

## 3. Target design

### 3.1 `OrderNode` (package-private, book layer)
```
final class OrderNode {
    final Order order;        // non-null for real nodes (Objects.requireNonNull)
    OrderNode prev, next;
    PriceLevel level;         // null when unlinked, and for sentinels
    // sentinel construction via a separate static factory / private constructor
}
```
A null `order` must only ever mean "sentinel", enforced by construction.

### 3.2 `PriceLevel`
State: `price`, sentinel `head`, sentinel `tail`, `totalVolume`, `noOfOrders`.

| Method | Behaviour |
|--------|-----------|
| `OrderNode addOrder(Order)` | Check price match and volume overflow **before mutating**; create node; link before `tail`; `totalVolume += remaining`; `noOfOrders++`; set `node.level = this`; return node. |
| `Order peekFirstOrder()` | `head.next`; `null` if it is `tail`. Throws if head has 0 remaining (head is zero-remaining only between a fill and `removeOrder`). |
| `long fillHeadOrder(long q)` | Peek head, fill `min(remaining, q)`, `totalVolume -= filled`, return filled. **No removal.** |
| `void unlink(OrderNode n)` | Guards (D9); splice neighbours; `totalVolume -= n.order.getRemainingQuantity()`; `noOfOrders--`; null `prev/next/level`. |
| `boolean isFullyConsumed()` | `head.next == tail` (optionally assert `tail.prev == head`). Note: a level whose head is zero-remaining but not yet unlinked is *not* fully consumed by this definition. |

**Volume invariant:** `totalVolume` equals the sum of remaining quantity of linked nodes at all times. A filled order has remaining 0 (the fill already reduced volume), so `unlink` subtracts 0; a cancelled order subtracts what it still had live.

### 3.3 `OrderBook`
State: `bids`, `asks` `TreeMap`s; `Map<Long, OrderNode> orders`.

| Method | Behaviour |
|--------|-----------|
| `void add(Order)` | Order of operations: reject zero remaining -> reject duplicate id (`containsKey`) -> get/create level -> `node = level.addOrder(order)` -> `orders.put`. A throw must never leave an empty level in the TreeMap or a stale map entry (add a test). |
| `void removeOrder(long orderId)` (internal) | Id **must** be present (missing = broken invariant: throw). `node.level.unlink(node)`; remove map entry; if level `isFullyConsumed()`, remove it from the correct `TreeMap` **by (side, price)** (from `node.order`). |
| `cancel(long orderId)` (external) | Unknown id is a normal business outcome: return a reject, never throw. Otherwise delegate to `removeOrder`. Result type not yet defined (see open threads). |
| `removePriceLevel` | Replace/extend: removal must work for **any** level, not just the best. |

Incoming orders that fully fill never rest, so they are never in the map and never reach `removeOrder`.

### 3.4 `MatchingEngine` (changes from v3)
```java
// match(): peek BEFORE or AFTER fillHeadOrder is now both safe (it no longer removes);
// the resting reference is still needed for buy/sell ids and for removeOrder.
Trade match(Order incoming, PriceLevel restingPL) {
    Order resting = restingPL.peekFirstOrder();
    // buy/sell id assignment branches on incoming.getSide()  (unchanged)
    long qty = restingPL.fillHeadOrder(incoming.getRemainingQuantity());
    incoming.fill(qty);
    Trade t = new Trade(qty, restingPL.getPrice(), buyId, sellId, /* command timestamp, see §4 */);
    if (resting.getRemainingQuantity() == 0) orderBook.removeOrder(resting.getOrderId());
    return t;
}
```
`submit()`: **delete** the `if (restingLevelOpt.get().isFullyConsumed()) orderBook.removePriceLevel(restingSide)` block. Left in, it would run against the *new* best level after `removeOrder` already removed the drained one, and throw. The loop re-fetches `bestLevel(restingSide)` each iteration, so it stays correct. Rest of `submit()` (single fetch per iteration, `add()` of any remainder) is unchanged.

`crosses(Order, PriceLevel)` delegates to `crosses(long incomingPrice, long restingPrice, Side restingSide)` (BUY resting: `incoming <= resting`; SELL resting: `incoming >= resting`).

### 3.5 Flows
- **Fill path:** `match()` -> `fillHeadOrder(q)` -> `incoming.fill(q)` -> build `Trade` -> if resting remaining == 0, `book.removeOrder(id)`. Nothing may peek the level between the fill and the removal.
- **Cancel path:** `cancel(id)` -> lookup -> `removeOrder(id)` (same unlink and cleanup).

---

## 4. Log, replay, determinism

- Sequenced **input commands** (new order, cancel) are the source of truth; outputs (acks, trades, fills, market data) are derived by replay.
- **Write-ahead:** log a command before the engine processes it.
- **Poison pill:** a command that crashes the engine is already logged and will crash replay too. Needs a recovery policy (skip/quarantine the last command).
- **Reject split:** *stateless* rejects (quantity <= 0, malformed) are caught at the gateway and never enter the log; *state-dependent* rejects (unknown cancel id, duplicate id if client-supplied) are decided by the engine and stay in the log.
- **Determinism rules:** anything the engine would generate must be stamped on the command *before* logging: sequence number, timestamp, order id. No wall-clock, randomness or HashMap-iteration-order dependence in matching (the id map is looked up, never iterated, in matching). Single writer thread.
- **Known gaps vs these rules:** (1) `match()` uses `Instant.now()`; replay would produce different trade timestamps. Fix: use the incoming command's timestamp. (2) The engine currently generates order ids (from 1); under this design the sequencer stamps them.
- **Log event shape (outputs):** an order that never rests goes received -> match(es) -> done; "open/rested" appears only for orders that go on the book (Coinbase-feed style). Do not fabricate rested/removed events.

---

## 5. Implementation plan (this spec's build order)

*Status (2026-10-14):* steps 1 and 2 are written (by explicit one-off exception, the LLM wrote the linked-list code after a Q&A round on its edge cases): `OrderNode.java`, sentinel-based `PriceLevel.java`, `PriceLevelTest.java` (26 tests, all passing in a scratch build; 9 engine tests fail until step 4, as expected). Pasted into the repo.
*Status (2026-10-17):* **steps 1–4 done; O(1) cancel complete.** Full suite green: 109 tests (adds `OrderBookTest`, `MatchingEngineBookCleanupTest`). `match()` removes fully filled resting orders via `removeOrderFromBook`; `process()` no longer removes levels; `removePriceLevel` is private and checks the `TreeMap.remove` return value (D18). Naming in code: `add` -> `addOrderToBook`, `removeOrder` -> `removeOrderFromBook`; this spec keeps the short names. Leftover cleanup: `removePriceLevel` still has redundant checks that `pl != priceLevel` already covers. Step-1 API: `OrderNode addOrder(Order)` (package-private, also rejects zero remaining), `void unlink(OrderNode)`, package-private test walks `ordersHeadToTail()` / `ordersTailToHead()`.

1. **`OrderNode` + linked-list `PriceLevel`.** Tests: add; unlink head / middle / tail / only node; `totalVolume` and `noOfOrders` after each; double-unlink throws; sentinel unlink throws; foreign-level node throws; `peekFirstOrder` on empty returns null; `isFullyConsumed`.
2. **Slim `fillHeadOrder()`.** Tests: partial fill; full fill leaves a zero-remaining head until removed; `peekFirstOrder` throws in that window.
3. **`OrderBook`:** map, `add()` with duplicate-id rejection and failure-atomicity, `removeOrder(id)` with eager cleanup by (side, price), `cancel(id)` with reject for unknown. Tests: cancel emptying a level removes it; cancel in a multi-order level leaves the level; cancel of filled/unknown id rejects; removal of a non-best level.
4. **Update `match()` and `submit()`** per §3.4 (add `removeOrder`, delete the old `removePriceLevel` block); re-run all existing engine tests.

(The week-level roadmap is in `ExchangeServer_Vision_v3.md`.)

---

## 6. Open threads

**Closed since v5 was written** (roadmap step 1 complete, 2026-10-17; 133 tests green)
- ~~`crosses()` input guard~~: added, then **removed by D19** (inputs are always valid `Order` prices); its tests deleted.
- ~~`crosses()` tests~~: all 6 logical cases active, plus the price-`1` boundary.
- ~~Delegate `createOrder(OrderRequest)`~~: done.
- ~~`OrderRequest` lacks `quantity > 0` guard~~: fixed; `OrderRequestTest` covers every boundary.
- ~~Where invariants live / limits disagree~~: resolved by D19, D20; `OrderTest` and `OrderRequestTest.AgreesWithOrder` pin the boundaries.

**Carried over**
- `Order` constructor does not reject a `null` side (`OrderRequest` does). Per D19 it should: a null side currently passes `process()` and is treated as SELL. Add the check plus a test.
- `Main.java` not yet written (may be superseded by the TCP gateway; confirm).

**New**
- ~~Duplicate order id policy~~: resolved by D15/D16.
- ~~Result type for `cancel()`~~: resolved by D17.
- Participant id on `Order`: needed for both `NOT_OWNER` cancel rejects (D17) and self-trade checks. When added, decide whether a non-owner cancel returns `NOT_OWNER` or `UNKNOWN_ORDER` (the latter avoids revealing that someone else's order id exists).
- Self-trade checks (named in D13's earlier wording) need an owner/participant id, which `Order` does not have. Add one, or drop self-trade prevention from scope.
- Tension with principle 2: `PriceLevel.fillHeadOrder()` holds fill logic and `match()` calls into `PriceLevel` directly. Acceptable, or should `OrderBook` expose the fill?
- Thread model between gateway connections, sequencer and the single writer (needed at roadmap step 4).
- `noOfOrders` becomes an explicit counter with the linked list (currently derived from `LinkedList.size()`).
- Test files are split per method/step for now; merge to one file per class at project completion (Vision v3 §7).
- Build tooling: there is no Maven/Gradle build file. JUnit 6 is wired only through IntelliJ's `Exchange Server.iml`, so VS Code (which does not read `.iml`) cannot resolve `org.junit`. Short term: `java.project.referencedLibraries` in `.vscode/settings.json`, or use IntelliJ for tests. Longer term: a `pom.xml`/`build.gradle` would give one classpath for both IDEs and the command line, and is needed anyway for JMH at step 6.
- Real-world anchors (from a note in `MatchingEngine.java`): typical lot sizes, share counts of large trades, tick sizes — how they should shape quantity/price limits here.
- Poison-pill recovery policy.
- Replace `Instant.now()` in `match()` with the command timestamp.
- Spring Boot vs plain TCP gateway: see Vision v3 §8.

---

## 7. Suggested opening message for the next chat

> Attach `ExchangeServer_Vision_v3.md` and `ExchangeServer_Spec_v5.md`. We are starting step 1: I will draft `OrderNode` and the sentinel-based `PriceLevel` and paste them for review against the edge cases in section 5. Paste current `Order`, `PriceLevel`, `OrderBook`, and `MatchingEngine` alongside, since GitHub pages cannot be fetched automatically.
