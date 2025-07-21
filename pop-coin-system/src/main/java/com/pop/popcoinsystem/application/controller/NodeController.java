package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.service.MiningService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pop/node")
public class NodeController {

    @Resource
    private MiningService miningService;
    @Resource
    private BlockChainService blockChainService;

    //对这些接口要做权限认证
    /**
     * 启动POP网络节点
     */
    @PostMapping("/start")
    public String start() {

        return "";
    }


    /**
     * 停止网络节点
     */


    /**
     * 修改网络配置 并重启
     */


    /**
     * 获取节点信息
     */


    /**
     * 设置该节点的挖矿信息
     */
    @PostMapping("/setMiner")
    public Result<String> setMiner(@RequestBody Miner miner){
        return miningService.setMiningInfo(miner);
    }



    /**
     * 是否启动挖矿
     */



    /**
     * 获取交易池中的交易
     */




}
