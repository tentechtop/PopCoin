package com.pop.popcoinsystem.application.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/blockchain")
public class NetworkController {



    /**
     * 启动节点
     */
    @RequestMapping("/start")
    public String start() {

        return "";
    }



}
