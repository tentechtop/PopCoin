package com.pop.popcoinsystem.application.controller;

import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.SyncBlockChainService;
import jakarta.websocket.server.PathParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestMessageController {

    @Autowired
    private SyncBlockChainService syncBlockChainService;

    @GetMapping("/send/{message}")
    public Result sendTextMessage(@PathVariable("message") String  message) throws Exception {
        return syncBlockChainService.sendTextMessage( message);
    }
}
