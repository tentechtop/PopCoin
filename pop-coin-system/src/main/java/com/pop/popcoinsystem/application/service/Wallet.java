package com.pop.popcoinsystem.application.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
@Data
public class Wallet implements Serializable {
    private String name;//钱包名称
    private String privateKeyHex;
    private String publicKeyHex;

    //钱包类型
    /**
     * {@link WalletType}
     */
    private int walletType;

    private String password;

    private String passwordHash;

    //总额 单位聪
    private long total;





}
