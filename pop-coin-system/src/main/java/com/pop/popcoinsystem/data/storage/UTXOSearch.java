package com.pop.popcoinsystem.data.storage;

import lombok.Data;

import java.util.Set;

@Data
public class UTXOSearch {

    private long total;//总额

    private Set<String> utxos;// utxo key集合
}
