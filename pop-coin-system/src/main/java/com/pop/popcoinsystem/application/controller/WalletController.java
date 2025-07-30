package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.application.service.Wallet;
import com.pop.popcoinsystem.application.service.WalletService;
import com.pop.popcoinsystem.application.service.WalletVO;
import com.pop.popcoinsystem.application.service.vo.BuildWalletUTXODTO;
import com.pop.popcoinsystem.application.service.vo.TransferVO;
import com.pop.popcoinsystem.application.service.vo.WalletBalanceVO;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 如果节点是你的，它当然可以做为一个钱包为你提供服务
 */
@Slf4j
@RestController
@RequestMapping("/pop/wallet")
public class WalletController {
    @Resource
    private WalletService walletService;

    /**
     * 创建钱包
     */
    @PostMapping("/create")
    public Result<Wallet> createWallet(@RequestBody WalletVO wallet) {
        return walletService.createWallet(wallet);
    }


    /**
     * 发送
     */
    @PostMapping("/send")
    public String send() {
        return "send success";
    }

    /**
     * 接收
     */
    @PostMapping("/receive")
    public String receive() {
        return "receive success";
    }

    /**
     * 获取当前钱包余额
     */
    @PostMapping("/getBalance")
    public Result getBalance(@RequestBody WalletBalanceVO walletBalanceVO) {
        return walletService.getBalance(walletBalanceVO);
    }

    /**
     * 查询钱包交易记录
     */
    @PostMapping("/getTransaction")
    public String getTransaction() {
        return "get transaction success";
    }


    /**
     * 根据公钥生成P2PKH类型地址
     */
    @PostMapping("/createP2PKHAddressByPK")
    public String createP2PKHAddressByPK() {
        return "create P2PKH address by PK success";
    }


    /**
     * 根据公钥生成P2SH类型地址
     */
    @PostMapping("/createP2SHAddressByPK")
    public String createP2SHAddressByPK() {
        return "create P2SH address by PK success";
    }


    /**
     * 根据公钥生成P2WPKH类型地址 （隔离见证v0）
     */
    @PostMapping("/createP2WPKHAddressByPK")
    public String createP2WPKHAddressByPK() {
        return "create P2WPKH address by PK success";
    }

    /**
     * 根据公钥生成P2WPKH类型公钥哈希
     */
    @PostMapping("/createP2WPKHByPK")
    public String createP2WPKHByPK() {
        return "create P2WPKH by PK success";
    }


    /**
     * 发起一笔交易  根据收款方地址确定是
     */
    @PostMapping("/createTransaction")
    public Result<TransactionDTO> createTransaction(@RequestBody TransferVO transferVO) {
        return walletService.createTransaction(transferVO);
    }


    /**
     * 构建钱包的UTXO
     * 挖矿使用的钱包是 BTCMiner
     *
     */
    @PostMapping("/buildWalletUTXO")
    public Result<String> buildWalletUTXO(@RequestBody BuildWalletUTXODTO buildWalletUTXODTO) {
        return walletService.buildWalletUTXO(buildWalletUTXODTO);
    }





}
