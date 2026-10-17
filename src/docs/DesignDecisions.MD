[//]: # (12/10/26)


# Operations - 1

    1. Skipping pre-validated data -> Redundant checks can be kept, as they only take nanoseconds for a single
    check, helping keep the OrderBook valid. Heavy checks need to be recorded and asserted at every layer, not
    re-validated.
    2. RemoveOrder will now move through the OrderBook before reaching PriceLevel --> O(1) cancel handling
    3. O(1) cancel handling ->

# PriceLevel - 2

    Using a linked list instead of a queue to store orders --> O(1) cancel requirement.

    