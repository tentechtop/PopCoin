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
     * 发送
     */

    /**
     * 接收
     */

    /**
     * 获取钱包余额
     */


}
