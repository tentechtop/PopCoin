package com.pop.popcoinsystem.network.protocol.messageData;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class HeadersRequestParam implements Serializable {

   private byte[] start;
   private byte[] end;

}
