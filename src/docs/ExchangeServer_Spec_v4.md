# ExchangeServer — Spec v4

**Supersedes:** Spec v3 (carry over anything from v3 not contradicted here)
**Companion:** `ExchangeServer_Vision_v2.md`
**Scope of this version:** O(1) cancel design, book-layer ownership of order lifecycle, input-log/replay design, determinism rules. Design is settled; implementation of step 1 is next.

---

## 1. Where we are

**Implemented (from earlier sessions)**
- Domain model: `Side`, `Order`, `Trade`, `PriceLevel`, `OrderBook`
- `MatchingEngine`: `submit()`, `match()`, `crosses()`; price-time priority; trades execute at the resting order's price
- `Order.MAX_ORDER_QUANTITY = 1_000_000_000L` with an overflow guard in `PriceLevel.addOrder()`
- `OrderRequest` as API entry point (`process(Order)`, `createOrder` overloads)
- JUnit 5 with `@Nested` two-level grouping

**Current code facts that drive this spec** (as of the code reviewed this session)
- `Order`: final `orderId`, `side`, `priceTicks`, `originalQuantity`, `timestamp` (all passed in from outside, which is good for determinism); mutable `filledQuantity`; `fill(q)` rejects non-positive and overfill.
- `PriceLevel`: `ArrayDeque<Order>`, `totalVolume`, `noOfOrders` (currently always 0, updates are commented out). `fillHeadOrder()` fills the head and **removes it itself** when exhausted. `peekFirstOrder()` throws if the head has zero remaining.
- `OrderBook`: bids `TreeMap` with reverse-order comparator, asks `TreeMap`; `add()`, `bestBid/Ask/Level`, `getVolume`, `removePriceLevel(Side)` (**removes only the best level**).
- `MatchingEngine.match(incoming, restingPL)`: peeks resting head, calls `fillHeadOrder`, fills incoming, builds `Trade` with `Instant.now()`. `OrderBook` is an instance field of `MatchingEngine`.

---

## 2. Decisions (settled this session)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **`OrderBook` owns the `orderId -> OrderNode` map.** It is the single place an order's life on the book ends. | Map invariant ("id in map iff order rests in a level") can only be kept by the code that also mutates levels. `MatchingEngine` must not touch the map. |
| D2 | **One `unlink(node)` in `PriceLevel`** serves both fill-removal and cancel. | Removing the head is a special case of unlinking; one code path means one place to maintain invariants. Cleanup after unlink is identical on both paths. |
| D3 | **`fillHeadOrder()` only fills** (peek, fill, subtract from `totalVolume`, return quantity). It no longer removes. | Otherwise `OrderBook` never learns of the removal and the map goes stale. |
| D4 | **After a fill, `match()` calls `book.removeOrder(...)` if the resting order has 0 remaining.** `match()` signature unchanged. | `OrderBook` is already a field of `MatchingEngine`. |
| D5 | **Node wrapper `OrderNode`** (not back-references in `Order`). The map stores `OrderNode`. | Keeps list machinery in the book layer; `Order` stays a clean domain object with final fields. Cost: one extra object and pointer hop per resting order. |
| D6 | **Self-implemented intrusive doubly linked list** in `PriceLevel`. | `java.util.LinkedList` hides its nodes, so there is no O(1) removal of an interior node you already hold. |
| D7 | **Sentinel head/tail nodes** in `PriceLevel`. | `unlink` becomes branch-free for head/tail/only-node cases. |
| D8 | **`unlink` nulls the node's `prev`, `next`, `level`.** | Prevents dangling pointers; makes stale nodes detectable. |
| D9 | **`unlink` rejects** (a) nodes whose `order == null` (sentinels/corruption) and (b) nodes whose `level != this` (stale or foreign). Throws `IllegalStateException`. | A cleared node looks like "only node in the level" (`prev == next == null`); without the `level` check a double-unlink would silently wipe `head`/`tail`. |
| D10 | **Eager removal of emptied price levels.** The book never contains empty levels. | Strict invariant, simple `bestBid/bestAsk`. Cost: O(log L) `TreeMap.remove` only on cancels/fills that empty a level. |
| D11 | **Cached best-level pointer deferred to benchmarking.** | `TreeMap.firstEntry()` is O(log L); L is small; measure before optimizing. |
| D12 | **Log source of truth = sequenced input commands**, replayed through the real engine. No separate replay methods. | Live and replay paths cannot drift. Requires a deterministic engine. |
| D13 | **Rejected commands that depend on engine state stay in the log.** | e.g. cancel of unknown id depends on book state, so it cannot be filtered before sequencing. |

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
| `OrderNode addOrder(Order)` | Check price match and volume overflow; create node; link before `tail`; `totalVolume += remaining`; `noOfOrders++`; set `node.level = this`; return node. |
| `Order peekFirstOrder()` | `head.next`; return `null` if it is `tail`. Throws if head has 0 remaining (head is only zero-remaining between a fill and `removeOrder`). |
| `long fillHeadOrder(long q)` | Peek head, fill `min(remaining, q)`, `totalVolume -= filled`, return filled. **No removal.** |
| `void unlink(OrderNode n)` | Guards (D9); splice neighbours; `totalVolume -= n.order.getRemainingQuantity()`; `noOfOrders--`; null `prev/next/level`. |
| `boolean isFullyConsumed()` | `head.next == tail` (optionally assert `tail.prev == head`). |

**Volume invariant:** `totalVolume` equals the sum of remaining quantity of live nodes at all times. A filled order has remaining 0 (the fill already reduced volume), so `unlink` subtracts 0; a cancelled order subtracts what it still had live.

### 3.3 `OrderBook`
State: `bids`, `asks` `TreeMap`s; `Map<Long, OrderNode> orders`.

| Method | Behaviour |
|--------|-----------|
| `void add(Order)` | Reject zero remaining; reject duplicate id (`putIfAbsent`); get/create level; `node = level.addOrder(order)`; put in map. |
| `void removeOrder(long orderId)` (internal) | Id **must** be present (missing means broken invariant: assert/throw). `node.level.unlink(node)`; remove map entry; if level `isFullyConsumed()`, remove it from the correct `TreeMap` **by (side, price)**. |
| `cancel(long orderId)` (external entry) | Unknown id is a normal business outcome: return a reject, never throw. Otherwise delegate to `removeOrder`. |
| `removePriceLevel` | Replace/extend: removal must work for **any** level, not just the best one. |

Incoming orders that fully fill never rest, so they are never in the map and never reach `removeOrder`.

### 3.4 Flows
- **Fill path:** `match()` -> `restingPL.fillHeadOrder(q)` -> `incoming.fill(q)` -> build `Trade` -> if resting remaining == 0, `book.removeOrder(restingOrder.getOrderId())`. Nothing may peek the level between the fill and the removal.
- **Cancel path:** `cancel(id)` -> lookup -> `removeOrder(id)` (same unlink and cleanup).

---

## 4. Log, replay, determinism

- The sequenced **input commands** (new order, cancel) are the source of truth; outputs (acks, trades, fills, market data) are derived by replay.
- **Write-ahead:** log a command before the engine processes it.
- **Poison pill:** a command that crashes the engine is already in the log and will crash replay too. Recovery needs a policy (skip/quarantine the last command).
- **Reject split:** *stateless* rejects (quantity <= 0, malformed) are caught at the gateway and never enter the log; *state-dependent* rejects (unknown cancel id, self-trade checks) are decided by the engine and stay in the log.
- **Determinism rules:** anything the engine would generate must be stamped on the command *before* logging: sequence number, timestamp, order id. No wall-clock, randomness, or HashMap-iteration-order dependence inside matching. Single writer thread.
- **Known gap:** `match()` uses `Instant.now()` for the trade timestamp. Replay would produce different trade timestamps. Fix: use the incoming command's timestamp.
- **Log event shape (outputs):** an order that never rests goes received -> match(es) -> done; "open/rested" only appears for orders that actually go on the book (Coinbase-feed style). Do not fabricate rested/removed events.

---

## 5. Implementation plan (build order)

1. **`OrderNode` + linked-list `PriceLevel`.** Tests: add; unlink head / middle / tail / only node; `totalVolume` and `noOfOrders` after each; double-unlink throws; sentinel unlink throws; foreign-level node throws; `peekFirstOrder` on empty returns null; `isFullyConsumed`.
2. **Slim `fillHeadOrder()`.** Tests: partial fill; full fill leaves a zero-remaining head until removed; `peekFirstOrder` throws in that window.
3. **`OrderBook`:** map, `add()` with duplicate-id rejection, `removeOrder(id)` with eager level cleanup by (side, price), `cancel(id)` with reject for unknown. Tests: cancel emptying a level removes it; cancel in a multi-order level leaves the level; cancel of filled/unknown id rejects.
4. **Update `match()` and the matching loop** to call `removeOrder`; re-run all existing engine tests.

---

## 6. Open threads

**Carried over (not touched this session)**
- Delegate `createOrder(OrderRequest)` to `createOrder(Side, long, long)` to remove duplicated validation.
- `OrderRequest` constructor lacks a `quantity > 0` guard (bug). Related design question: where do invariants live: `OrderRequest`, the `createOrder` path, or `Order`? `Order` already enforces them in its constructor. Stateless checks belong at the gateway; state-dependent ones in the engine.
- `crosses()` test coverage.

**New**
- Duplicate order id policy: reject while resting (decided in `add()`); can an id be reused after fill/cancel? (undecided)
- `noOfOrders` is currently dead; becomes live with the linked list.
- Poison-pill recovery policy.
- Replace `Instant.now()` in `match()` with the command timestamp.

---

## 7. One-week plan (about 5h/day)
1. Close open threads and O(1) cancel (this spec)
2. Order types / cancel flows
3. Sequencer + event log
4. TCP gateway with single-writer engine thread
5. Risk checks + market data
6. Benchmarking (decide on cached-best-level pointer here)
7. Crash recovery via replay

---

## 8. Suggested opening message for the next chat

> Attach `ExchangeServer_Vision_v2.md` and `ExchangeServer_Spec_v4.md`. We are starting step 1: I will draft `OrderNode` and the sentinel-based `PriceLevel` and paste them for review against the edge cases in section 5. Paste current `Order`, `PriceLevel`, `OrderBook`, and `MatchingEngine` alongside, since GitHub pages cannot be fetched automatically.
