package io.ep2p.kademlia.protocol.message;

import com.google.common.base.Objects;
import io.ep2p.kademlia.protocol.message.DHTLookupKademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Transaction implements Serializable {
    private static final long serialVersionUID = 1L;

    private  String  dataValue;

    private List<TxOut> OTXO;

    @Override
    public int hashCode() {
        return Objects.hashCode(dataValue);
    }
}
