package com.pop.popcoinsystem.application.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pop/node")
public class NodeController {

    //对这些接口要做权限认证
    /**
     * 启动POP网络节点
     */
    @RequestMapping("/start")
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
     * 获取交易池中的交易
     */




}
