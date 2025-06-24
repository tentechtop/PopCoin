package io.ep2p.kademlia.protocol.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TxOut {
    private String address;
    private String amount;
    private String txId;
}
