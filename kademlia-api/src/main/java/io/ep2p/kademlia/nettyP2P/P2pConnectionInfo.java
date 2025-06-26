package io.ep2p.kademlia.nettyP2P;

import com.google.common.base.Objects;
import io.ep2p.kademlia.connection.ConnectionInfo;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class P2pConnectionInfo implements ConnectionInfo {
    private String host;
    private int port;
    private int score;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        P2pConnectionInfo that = (P2pConnectionInfo) o;
        return getPort() == that.getPort() && Objects.equal(getHost(), that.getHost());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getHost(), getPort());
    }
}
