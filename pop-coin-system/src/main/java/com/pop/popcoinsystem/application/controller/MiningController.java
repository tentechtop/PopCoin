package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.mining.MiningStart;
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
    private MiningStart miningService;

    /**
     * 设置该节点的挖矿信息
     */
    @PostMapping("/setMiner")
    public Result<String> setMiner(@RequestBody Miner miner){
        return miningService.setMinerInfo(miner);
    }

    /**
     * 启动
     * @param miner
     * @return
     * @throws Exception
     */
    @PostMapping("/startMining")
    public Result<String> startMining(@RequestBody Miner miner) throws Exception {
        return miningService.startMining(miner);
    }

    /**
     * 暂停
     */
    @PostMapping("/pauseMining")
    public Result<String> pauseMining() throws Exception {
        return miningService.pauseMining();
    }

    /**
     * 恢复
     */
    @PostMapping("/resumeMining")
    public Result<String> resumeMining() throws Exception {
        return miningService.resumeMining();
    }


    /**
     * 终止
     * @return
     * @throws Exception
     */
    @PostMapping("/stopMining")
    public Result<String> stopMining() throws Exception {
        return miningService.stopMining();
    }





}
