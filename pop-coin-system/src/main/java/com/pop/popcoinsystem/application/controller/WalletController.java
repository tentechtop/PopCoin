package com.pop.popcoinsystem.application.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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




}
