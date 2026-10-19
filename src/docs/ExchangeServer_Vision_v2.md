# ExchangeServer — Vision v2

**Supersedes:** `ExchangeServer_Vision.md` (carry over anything from v1 not contradicted here)
**Companion:** `ExchangeServer_Spec_v4.md`

---

## 1. Purpose

ExchangeServer is a Java trade-exchange matching engine built as a **hands-on way to understand production-grade software systems**. The goal is depth of understanding over feature count: every design choice should be justified, tested, and understood well enough to defend.

It is deliberately worked through in a Socratic style: decisions come from reasoning about invariants and trade-offs, not from copying a known design.

---

## 2. What the system is

A single-instrument limit order book with a price-time-priority matching engine, growing toward a complete exchange pipeline:

```
 client --> TCP gateway --> sequencer + input log --> single-writer engine --> OrderBook
              (stateless          (stamps seq, ts,         (deterministic        (levels, nodes,
               validation)         id; write-ahead)         matching)             id map)
                                         |                       |
                                         v                       v
                                  replay on recovery      outputs: acks, trades,
                                                           fills, market data
```

Status: domain model, matching engine, and API entry point exist. The book is being reworked for O(1) cancel (see Spec v4). Sequencer, log, gateway, risk checks, market data, benchmarking, and recovery are planned.

---

## 3. Guiding principles

1. **Invariants first.** State the invariant, find the single place that maintains it, and make violations fail loudly (assert/throw), never silently.
   - `totalVolume` equals live volume at all times.
   - The id map contains an id if and only if that order rests in a level.
   - The book never contains empty price levels.
2. **One owner per responsibility.** `OrderBook` owns the order lifecycle on the book; `PriceLevel` is a pure data structure; `Order` is a clean domain object; `MatchingEngine` decides matches but does not touch book internals.
3. **One code path where possible.** Fill-removal and cancel share a single `unlink`; replay shares the live engine path.
4. **Determinism.** The engine's behaviour depends only on the ordered input commands. Ids, timestamps, and sequence numbers are stamped before logging; no wall-clock reads, randomness, or hash-order dependence in matching; a single writer thread.
5. **Inputs are the source of truth.** Log sequenced commands; derive outputs by replay. Outputs are published but not authoritative.
6. **Validate at the right layer.** Stateless checks at the gateway; state-dependent checks in the engine. Internal invariant violations throw; external bad requests return rejects.
7. **Measure before optimizing.** Prefer the simple correct structure (eager level removal, `TreeMap`) and revisit with benchmarks (e.g. cached best-level pointer).
8. **Honest complexity claims.** Say what is actually O(1) and what is O(log L), and why.

---

## 4. Key design commitments (see Spec v4 for detail)

- Intrusive doubly linked list per price level with sentinel nodes; `OrderNode` wrapper owned by the book layer.
- `orderId -> OrderNode` map in `OrderBook`; cancel is O(1) except when a cancel empties a level (O(log L) `TreeMap` removal).
- Eager removal of emptied levels.
- Write-ahead input log; state-dependent rejects are logged, stateless rejects are filtered at the gateway.
- Log events for orders that never rest: received -> match(es) -> done, with no fabricated "rested/removed" events.

---

## 5. Non-goals (for now)

- Multi-instrument support, multi-threaded matching, or distributed replication.
- Price-indexed array books (requires a bounded price range; revisit only if benchmarks demand it).
- Full FIX/ITCH protocol compliance; a simple TCP protocol is enough.
- Production hardening beyond what the learning goals require.

---

## 6. Roadmap (one-week plan, about 5h/day)

1. Close open threads (validation deduplication, `OrderRequest` quantity guard, `crosses()` tests) and O(1) cancel
2. Order types / cancel flows
3. Sequencer + event log
4. TCP gateway with single-writer engine thread
5. Risk checks + market data
6. Benchmarking (decide on cached best-level pointer; measure level removal vs lookup cost)
7. Crash recovery via replay (including poison-pill policy)

---

## 7. Glossary

- **Resting order:** an order sitting on the book (passive side).
- **Incoming / aggressive order:** the order currently being matched; may never rest if fully filled.
- **Touch:** the best bid and best ask levels.
- **Unlink:** splicing a node out of its level's list in O(1) given the node.
- **Sentinel:** dummy head/tail node removing edge-case branching from list operations.
- **Poison pill:** a logged command that crashes the engine and therefore crashes every replay.
- **Write-ahead:** persisting a command before processing it.
