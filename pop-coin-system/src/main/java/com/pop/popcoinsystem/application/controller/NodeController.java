package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.service.MiningService;
import com.pop.popcoinsystem.service.NodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/node")
public class NodeController {
    @Resource
    private NodeService nodeService;

    /**
     * 获取节点列表
     */











}
