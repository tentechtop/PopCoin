package com.pop.popcoinsystem.application.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pop/blockchain")
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
     * 根据范围查询区块
     */
    @RequestMapping("/getBlockByRange")
    public String getBlockByRange() {
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


    /**
     * 创建区块
     * 需要钱包地址
     */
    @RequestMapping("/createBlock")
    public String createBlock() {
        return "";
    }


    /**
     * 查询交易
     */
    @RequestMapping("/getTransaction")
    public String getTransaction() {
        return "";
    }


    /**
     * 查询地址中的余额
     */
    @RequestMapping("/getBalance")
    public String getBalance() {
        return "";
    }


    /**
     * 查询地址中的余额并带出未花费
     */
    @RequestMapping("/getBalanceWithUnspent")
    public String getBalanceWithUnspent() {
        return "";
    }



}
