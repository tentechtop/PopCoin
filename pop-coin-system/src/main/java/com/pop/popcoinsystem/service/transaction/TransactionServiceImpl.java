package com.pop.popcoinsystem.service.transaction;

import org.springframework.stereotype.Service;

@Service
public class TransactionServiceImpl implements TransactionService{
    public String sayHello(String name) {
        return "Hello, " + name + "!";
    }
}
