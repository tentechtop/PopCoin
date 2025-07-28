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
    private String amount;//转账金额

    //是否隔离见证 0否 1是
    @NonNull
    private int isSegWit;

}
