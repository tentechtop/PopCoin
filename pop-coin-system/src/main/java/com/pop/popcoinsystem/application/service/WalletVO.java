package com.pop.popcoinsystem.application.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;


@Data
public class WalletVO {
    private String name;//钱包名称

    private int walletType;

    private String password;
}
