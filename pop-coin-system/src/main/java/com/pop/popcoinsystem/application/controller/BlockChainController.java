package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.transaction.dto.UTXODTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;


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

    @GetMapping("/getTransactionBlock/{txId}")
    public Result<BlockDTO> getTransactionBlock(@PathVariable("txId") String txId) {
        if (txId == null){
            return Result.error("参数错误");
        }
        return blockChainService.getTransactionBlock(txId);
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
     * 根据范围查询区块
     */
    @GetMapping("/getBlockByRange/{start}/{end}")
    public Result getBlockByRange(@PathVariable("start") Long start , @PathVariable("end") Long end) {
        return blockChainService.getBlockByRange(start, end);
    }


    @GetMapping("/getAllUTXO")
    public Result getAllUTXO() {
        return blockChainService.getAllUTXO();
    }

    /**
     * 查询地址中的余额
     */
    @GetMapping("/getBalance/{address}")
    public Result getBalance(@PathVariable("address") String address) {
        return blockChainService.getBalance(address);
    }


}
