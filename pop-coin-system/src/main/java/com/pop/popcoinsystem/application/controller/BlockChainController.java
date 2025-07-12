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


    /**
     * 查询当前区块链信息
     */
    @RequestMapping("/getBlockChainInfo")
    public String getBlockChainInfo() {
        return "";
    }

    /**
     * 获取区块信息
     */
    @RequestMapping("/getBlock")
    public String getBlock() {
        return "";
    }

    /**
     * 查询最新的区块 最多100个
     */
    @RequestMapping("/getLatest100Blocks")
    public String getLatest100Blocks() {
        return "";
    }

    /**
     * 查询当前区块的前100个区块
     */
    @RequestMapping("/getPrevious100Blocks")
    public String getPrevious100Blocks() {
        return "";
    }


    /**
     * 同步区块链全部数据
     */
    @RequestMapping("/syncBlockChain")
    public String syncBlockChain() {
        return "";
    }

    /**
     * 同步区块链最新数据
     */
    @RequestMapping("/syncBlockChainLatest")
    public String syncBlockChainLatest() {
        return "";
    }

    /**
     * 同步区块头
     */
    @RequestMapping("/syncBlockHeaders")
    public String syncBlockHeaders() {
        return "";
    }


    /**
     * 获取网络节点列表
     */
    @RequestMapping("/getPeerList")
    public String getPeerList() {
        return "";
    }






}
