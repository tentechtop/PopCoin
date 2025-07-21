package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.block.BlockVO;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.BlockChainService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 为了可维护性 整个系统仅支持P2WPKH
 */

@RestController
@RequestMapping("/pop/blockchain")
public class BlockChainController {
    @Resource
    private BlockChainService blockChainService;

    /**
     * 查询区块高度
     */
    @PostMapping("/getBlockHeight")
    public Result<Long> getBlockHeight(@RequestBody BlockVO blockVO) {
        return blockChainService.getBlockHeight(blockVO.getHash());
    }

    /**
     * 查询当前区块链信息
     */
    @PostMapping("/getBlockChainInfo")
    public Result<BlockChain> getBlockChainInfo() {
        return blockChainService.getBlockChainInfo();
    }

    /**
     * 获取区块信息 根据区块hash HEX
     */
    @PostMapping("/getBlock")
    public Result<BlockDTO> getBlock(@RequestBody BlockVO blockVO) {
        return blockChainService.getBlock(blockVO);
    }

    /**
     * 查询最新的区块 最多100个
     */
    @PostMapping("/getLatest100Blocks")
    public String getLatest100Blocks() {
        return "";
    }

    /**
     * 查询当前区块的前100个区块
     */
    @PostMapping("/getPrevious100Blocks")
    public String getPrevious100Blocks() {
        return "";
    }

    /**
     * 根据范围查询区块
     */
    @PostMapping("/getBlockByRange")
    public String getBlockByRange() {
        return "";
    }


    /**
     * 同步区块链全部数据
     */
    @PostMapping("/syncBlockChain")
    public String syncBlockChain() {
        return "";
    }

    /**
     * 同步区块链最新数据
     */
    @PostMapping("/syncBlockChainLatest")
    public String syncBlockChainLatest() {
        return "";
    }

    /**
     * 同步区块头
     */
    @PostMapping("/syncBlockHeaders")
    public String syncBlockHeaders() {
        return "";
    }


    /**
     * 获取网络节点列表
     */
    @PostMapping("/getPeerList")
    public String getPeerList() {
        return "";
    }


    /**
     * 创建区块
     * 需要钱包地址
     */
    @PostMapping("/createBlock")
    public String createBlock() {
        return "";
    }

    /**
     * 创建创世区块
     */
    @PostMapping("/createGenesisBlock")
    public String createGenesisBlock() {
        return "";
    }


    /**
     * 查询交易
     */
    @PostMapping("/getTransaction")
    public String getTransaction() {
        return "";
    }


    /**
     * 查询地址中的余额
     */
    @PostMapping("/getBalance")
    public String getBalance() {
        return "";
    }


    /**
     * 查询地址中的余额并带出未花费
     */
    @PostMapping("/getBalanceWithUnspent")
    public String getBalanceWithUnspent() {
        return "";
    }



}
