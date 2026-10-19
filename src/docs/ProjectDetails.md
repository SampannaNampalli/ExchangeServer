# Stock Exchange API

### Invariants

1. OrderBook never contains an empty PriceLevel.
2. totalVolume reflects tradable volume.
3. MatchingEngine should decide what changes need to happen to the book, but OrderBook should execute those changes.

Order
1. Order should not know where it is stored.
2. which PriceLevel contains it 
3. whether it's in bids or asks 
4. which OrderBook contains it
5. An order should simply know - I represent an instruction to buy/sell.


### Elements

#### 1. Orders
    1. Directly add an order into the OrderBook using its 
    constructor.
    2. When filling an order, the totalVolume of the 
    PriceLevel the operated order belongs to must change. 
    Does not reflect the change currently.

#### 2. OrderBook

#### 3. PriceLevel
    1. The totalVolume must change when an order is filled.

#### 4. MatchingEngine

#### 5. Trade
    1. A trade if possible, happens at the price of the 
    order which arrived first.

### Future Scope
1. Using primitive maps to increase throughput.

### Questions
    
    1. Who owns volume updates?