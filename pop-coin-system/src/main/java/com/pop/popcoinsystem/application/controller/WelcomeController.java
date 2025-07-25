package com.pop.popcoinsystem.application.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@Slf4j
@RestController
@RequestMapping("")
public class WelcomeController {
    @GetMapping("")
    public String welcome(){
        return "Welcome to PopCoinSystem, a peer-to-peer electronic cash system";
    }

    /**
     * 通过私钥登录
     * 用户通过对登录信息进行签名登录
     *
     * @return
     */
    @PostMapping("/login")
    public String login(){
        return "Login";
    }

}
