# Stock Exchange API

### Invariants

1. OrderBook never contains an empty PriceLevel.
2. totalVolume reflects tradable volume.

### Elements

#### 1. Orders
    1. Directly add an order into the OrderBook using its constructor.

#### 2. OrderBook

#### 3. PriceLevel

#### 4. MatchingEngine

### Future Scope
1. Using primitive maps to increase throughput.