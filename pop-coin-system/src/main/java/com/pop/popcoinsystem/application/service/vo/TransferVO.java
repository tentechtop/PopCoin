package com.pop.popcoinsystem.application.service.vo;

import lombok.Data;
import lombok.NonNull;

@Data
public class TransferVO {

    @NonNull
    private String walletName;//钱包名称

    @NonNull
    private String toAddress;//转账地址

    @NonNull
    private long amount;//转账金额  单位聪


}
