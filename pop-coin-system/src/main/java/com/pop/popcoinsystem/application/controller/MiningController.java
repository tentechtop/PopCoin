package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.Mining;
import com.pop.popcoinsystem.service.MiningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/mining")
public class MiningController {
    @Autowired
    private MiningService miningService;

    /**
     * 设置该节点的挖矿信息
     */
    @PostMapping("/setMiner")
    public Result<String> setMiner(@RequestBody Miner miner){
        return miningService.setMinerInfo(miner);
    }

    @PostMapping("/startMining")
    public Result<String> startMining(@RequestBody Miner miner) throws Exception {
        return miningService.startMining(miner);
    }

    @PostMapping("/stopMining")
    public Result<String> stopMining() throws Exception {
        return miningService.stopMining();
    }





}
