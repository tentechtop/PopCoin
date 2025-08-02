package com.pop.popcoinsystem.network.protocol.messageData;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

@Data
@AllArgsConstructor
public class HeadersRequestParam implements Serializable {

   private byte[] start;
   private byte[] end;

}
