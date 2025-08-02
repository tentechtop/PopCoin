package com.pop.popcoinsystem.service.blockChain.strategy;

import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.UTXO;

public interface ScriptVerificationStrategy {
    boolean verify(Transaction tx, TXInput input, int inputIndex, UTXO utxo);
}
