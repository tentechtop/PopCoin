package com.pop.popcoinsystem.application.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/blockchain")
public class BlockChainController {

    /**
     * 查询区块高度
     */
    @RequestMapping("/getBlockHeight")
    public String getBlockHeight() {
        return "";
    }





}
