package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.SyncBlockChainServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestMessageController {

    @Autowired
    private SyncBlockChainServiceImpl syncBlockChainService;

    @GetMapping("/send/{message}")
    public Result sendTextMessage(@PathVariable("message") String  message) throws Exception {
        return syncBlockChainService.sendTextMessage( message);
    }

    @GetMapping("/findNode")
    public Result findNode() throws Exception {
        return syncBlockChainService.findNode();
    }

    @GetMapping("/getBlockByHash/{hash}")
    public Result getBlockByHash(@PathVariable("hash") String  hash) throws Exception {
        return syncBlockChainService.getBlockByHash(hash);
    }




}
