package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.block.BlockVO;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.transaction.dto.UTXODTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.Resource;
import jakarta.websocket.server.PathParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;


/**
 * 为了可维护性 整个系统仅支持P2WPKH
 */
@Slf4j
@RestController
@RequestMapping("/blockchain")
public class BlockChainController {
    @Lazy
    @Autowired
    private BlockChainService blockChainService;


    /**
     * 查询当前区块链信息
     */
    @GetMapping("/info")
    public Result<BlockChain> getBlockChainInfo() {
        return blockChainService.getBlockChainInfo();
    }

    /**
     * 根据区块hash HEX 获取区块信息
     */
    @GetMapping("/block/{blockHashHex}")
    public Result<BlockDTO> getBlock(@PathVariable("blockHashHex") String blockHashHex) {
        if (blockHashHex == null){
            return Result.error("参数错误");
        }
        return blockChainService.getBlock(blockHashHex);
    }

    @GetMapping("/block/height/{height}")
    public Result<BlockDTO> getBlock(@PathVariable("height") long height) {
        if (height < 0){
            return Result.error("参数错误");
        }
        return blockChainService.getBlock(height);
    }


    /**
     * 查询交易
     */
    @GetMapping("/transaction/{txId}")
    public Result<TransactionDTO> getTransaction(@PathVariable("txId") String txId) {
        if (txId == null){
            return Result.error("参数错误");
        }
        return blockChainService.getTransaction(txId);
    }
    /**
     * 查询UTXO
     */
    @GetMapping("/transaction/{txId}/{vout}")
    public Result<UTXODTO> getUTXO(@PathVariable("txId") String txId , @PathVariable("vout") int vout) {
        if (txId == null || vout < 0){
            return Result.error("参数错误");
        }
        UTXO utxo = blockChainService.getUTXO(CryptoUtil.hexToBytes(txId), vout);
        UTXODTO utxodto = BeanCopyUtils.copyObject(utxo, UTXODTO.class);
        return Result.ok(utxodto);
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
     * 查询地址中的余额
     */
    @PostMapping("/getBalance")
    public String getBalance() {
        return "";
    }












}
