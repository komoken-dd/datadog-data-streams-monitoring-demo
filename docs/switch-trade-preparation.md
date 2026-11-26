# Repeatable switch trades

Switch trades use a read-only preflight and actual sell executions; preparing a
presentation never resets cash, holdings, Kafka offsets, or financial tables.
The same algorithm works with the initially cash-poor accounts and with an
existing environment whose switch traders accumulated cash during older runs.

For each proposed switch trade the simulator:

1. Allows one in-flight switch per account and checks that the previous
   uncertain order reached a terminal outcome. It queries unknown outcomes;
   it never sends a replacement SELL or BUY automatically.
2. Reads buying power and sellable positions. An account with existing holds
   is skipped. If the proposed symbol is exhausted, it chooses another held
   symbol and caps the SELL to the available whole trading units.
3. Submits the SELL once with an idempotency key, retaining a stable inquiry
   identity even when its response is lost.
4. Polls order inquiry for the actual full fill and all execution records.
   An expired or partially filled sell does not trigger a follow-up BUY.
5. After the configured reflection grace period, sizes a marketable limit BUY
   using the pre-sale available cash plus the actual execution proceeds (with
   a small price safety margin). Its reservation must exceed the pre-sale
   cash, so a backlog in this sale's reflection still produces the intended
   insufficient-buying-power symptom even after days of prior traffic.
6. Retains the per-account lock until the BUY is filled, expired, rejected, or
   cancelled. A bounded inquiry timeout quarantines the account's pending
   order identity until a later read-only check observes completion.

The trade budget is capped at JPY 500 million and one million shares. When no
valid BUY needs these proceeds, the attempt is skipped explicitly instead of
presenting an unrelated rejection as reflection delay. No unusual input causes
an unbounded request queue: scheduler concurrency, polling time, and account
slots are bounded.

## What to inspect

`demo.brokerage.simulator.switch_trades` has bounded `result` values. In
addition to `accepted`, `sell_rejected`, and `rejected_buying_power`, preflight
and inquiry outcomes include `account_busy`, `preconditions_not_met`,
`previous_outcome_pending`, `sell_outcome_pending`, `buy_outcome_pending`,
`sell_not_filled`, `no_buy_requiring_proceeds`, and `inquiry_unavailable`.
A skipped precondition is not evidence that the injected delay worked.

Return to baseline and let reservations and lag drain before a presentation.
If preconditions remain unmet, inspect account holds, pending submissions, and
sellable positions through the authorized read-only APIs. Investigate orphaned
holds; do not release all reservations, truncate tables, or reset consumer
offsets to force the scenario to look healthy. A full environment reset is a
separate offline operation requiring a consistent database/Kafka lifecycle.

The three-second default grace now starts after confirmed SELL execution,
rather than after HTTP order acceptance. During healthy operation this avoids
BUY attempts based on a sell that has not filled yet. Under reflection-delay
load, the accumulating consumer backlog still prevents newly confirmed sale
proceeds from becoming spendable in time.
