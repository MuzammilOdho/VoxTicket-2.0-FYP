Refund creation: once approved, a refund is created immediately in the system with status PENDING.
Settlement time: card and wallet refunds typically take a few business days to post to the customer's account after being marked SUCCEEDED, depending on the bank or provider.
COD orders: cash-on-delivery orders that were never charged produce no refund, since no payment was collected.
Refund failure: a refund can fail with status FAILED due to a provider-side issue; this does not mean the money was lost, and the case should be investigated rather than retried automatically.
Partial refunds: a payment can have more than one refund; the payment is only fully REFUNDED once the total refunded amount equals the original payment amount.