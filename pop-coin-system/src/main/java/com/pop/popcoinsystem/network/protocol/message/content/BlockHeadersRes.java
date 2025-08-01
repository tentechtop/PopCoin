package com.pop.popcoinsystem.network.protocol.message.content;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockHeader;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
public class BlockHeadersRes implements Serializable {

    private byte[] start;
    private byte[] end;
    private List<Block> headers;

}
