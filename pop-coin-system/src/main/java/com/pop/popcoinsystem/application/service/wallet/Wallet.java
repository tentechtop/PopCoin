package com.pop.popcoinsystem.application.service.wallet;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
@Data
public class Wallet implements Serializable {
    private String name;//钱包名称
    private String privateKeyHex;
    private String publicKeyHex;

    /*测试多签 2-3*/
    private String privateKeyHex1;
    private String publicKeyHex1;

    private String privateKeyHex2;
    private String publicKeyHex2;

    //钱包类型
    /**
     * {@link WalletType}
     */
    private int walletType;

    private String password;

    private String passwordHash;

    //余额 单位聪
    private long  balance = 0L;





}
