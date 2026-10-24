# Step 3

## D: Replay

1. Will flow through Exchange.java before starting anew.
2. New class -> Separation of concerns/components.
3. Working:
    1. Read from the log.
    2. Process each command through the matching engine (same matching engine and sequencer must be used after replay has finished)
    3. return to driver class, and resume normal execution
    4. Maybe async functionality is required -> While replay is running, the client must know that they need to wait for some time
    5. Check for gaps and report if found.
    6. Since replay will feed commands directly to the matching engine, how to bring sequence numbers and order ids up to the newest order? -> Initialize at the start, and increment every time a command is replayed successfully OR
    New Sequencer() constructor that takes parameters.

## A: Commands as data

1. Create a new class -> Commands
2. Expose two factory constructors -> new-order and cancel-order
3. Update the matching engine to accept commands.


# Step 2

## Market

1. No price -> always crosses (as long as opposite level is not empty) -> modify crosses() in regards to MARKET orders.
2. go thru where
3. never rests -> Completely Filled => FILLED : Remaining => CANCELLED

## FOK

1. Check whether there is enough liquidity present at the current moment in the book -> fillable(long orderQuantity): boolean


## Return type of process(OrderRequest)

### Requirements:

0. Common: List<Trade>
1. LIMIT -> Whether the order was rested, rest quantity.
2. IOC -> Whether the order was fulfilled, or cancelled, cancelled quantity
3. FOK -> If completely filled, List<Trade>, no requirement of rested/cancelled status and quantity. If killed, no status requirement, no quantity requirement => Only List<Trade> if filled, empty otherwise, maybe boolean corresponding to fill/kill.
4. Market(Direct match, no resting) -> Never rests, direct cancel -> No requirement of status of leftover volume => only List<Trade>, maybe underived leftover volume quantity
5. Post-only(must rest) -> No List<Trade>, only boolean to check whether it rested or not.


## IOC

1. Change OrderRequest -> Add order type -> done
2. OrderType -> new Enum with types listed -> done
3. Null type of order in request becomes a limit order -> do it here or at the client-side?
4. Change Order -> Add OrderType as a field.
5. Same as limit except after all resting orders are exhausted, it must be cancelled
    1. Update existing methods to accept OrderType and the changed OrderRequest as an argument.
    2. cancel(orderId) should split into one for resting orders, and another for non-rested orders.
    3. Cancel for non-rested orders -> 
    cancel(Order): cancelResult
    4. Submit return type => {List<Trade>, OrderStatus, RemainingQuantity}
    5. OrderStatus => {Filled, Rested, Cancelled, Rejected}
    6. RemainingQuantity => long

