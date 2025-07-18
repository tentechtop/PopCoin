package com.pop.popcoinsystem.application.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 如果节点是你的，它当然可以做为一个钱包为你提供服务
 */
@Slf4j
@RestController
@RequestMapping("/pop/wallet")
public class WalletController {

    /**
     * 创建钱包
     */
    @RequestMapping("/create")
    public String createWallet() {
        return "create wallet success";
    }

    /**
     * 创建密码钱包
     */
    @RequestMapping("/createPasswordWallet")
    public String createPasswordWallet() {
        return "create password wallet success";
    }

    /**
     * 创建助记词钱包
     */
    @RequestMapping("/createMnemonicWallet")
    public String createMnemonicWallet() {
        return "create mnemonic wallet success";
    }


    /**
     * 创建密码助记词钱包
     */
    @RequestMapping("/createPasswordMnemonicWallet")
    public String createPasswordMnemonicWallet() {
        return "create password mnemonic wallet success";
    }

    /**
     * 发送
     */
    @RequestMapping("/send")
    public String send() {
        return "send success";
    }

    /**
     * 接收
     */
    @RequestMapping("/receive")
    public String receive() {
        return "receive success";
    }

    /**
     * 获取当前钱包余额
     */
    @RequestMapping("/getBalance")
    public String getBalance() {
        return "get balance success";
    }

    /**
     * 查询钱包交易记录
     */
    @RequestMapping("/getTransaction")
    public String getTransaction() {
        return "get transaction success";
    }


    /**
     * 根据公钥生成P2PKH类型地址
     */
    @RequestMapping("/createP2PKHAddressByPK")
    public String createP2PKHAddressByPK() {
        return "create P2PKH address by PK success";
    }


    /**
     * 根据公钥生成P2SH类型地址
     */
    @RequestMapping("/createP2SHAddressByPK")
    public String createP2SHAddressByPK() {
        return "create P2SH address by PK success";
    }


    /**
     * 根据公钥生成P2WPKH类型地址 （隔离见证v0）
     */
    @RequestMapping("/createP2WPKHAddressByPK")
    public String createP2WPKHAddressByPK() {
        return "create P2WPKH address by PK success";
    }

    /**
     * 根据公钥生成P2WPKH类型公钥哈希
     */
    @RequestMapping("/createP2WPKHByPK")
    public String createP2WPKHByPK() {
        return "create P2WPKH by PK success";
    }


}
