package com.pop.popcoinsystem.application.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
@Data
public class Wallet implements Serializable {
    public String name;//钱包名称
    public String privateKeyHex;
    public String publicKeyHex;
}
