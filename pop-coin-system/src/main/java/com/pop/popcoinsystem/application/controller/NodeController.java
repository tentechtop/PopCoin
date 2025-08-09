package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/node")
public class NodeController {


    @Lazy
    @Autowired
    private NodeService nodeService;

    //获取节点列表
    @RequestMapping("/list")
    public Result list(){
        return nodeService.list();
    }



}
