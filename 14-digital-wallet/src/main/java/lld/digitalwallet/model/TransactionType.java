package lld.digitalwallet.model;

/** The kind of top-level {@link Transaction}. */
public enum TransactionType {
    /** External funding in (e.g. linked bank account top-up, cash deposit). */
    DEPOSIT,
    /** External funding out (e.g. cash-out/withdrawal to bank). */
    WITHDRAWAL,
    /** Internal wallet-to-wallet movement; always two {@link LedgerEntry} rows. */
    TRANSFER,
    /** A compensating transaction that undoes a previously completed transaction. */
    REVERSAL
}
