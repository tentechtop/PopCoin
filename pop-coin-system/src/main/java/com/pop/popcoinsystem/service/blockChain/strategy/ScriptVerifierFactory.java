package com.pop.popcoinsystem.service.blockChain.strategy;

import com.pop.popcoinsystem.data.script.ScriptType;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
@Service
public class ScriptVerifierFactory {

    @Autowired
    private P2PKHVerifier p2PKHVerifier;
    @Autowired
    private P2WPKHVerifier p2WPKHVerifier;


    private static final Map<ScriptType, ScriptVerificationStrategy> STRATEGIES = new HashMap<>();

    @PostConstruct
    public void inti(){
        STRATEGIES.put(ScriptType.P2PKH, p2PKHVerifier);
        STRATEGIES.put(ScriptType.P2WPKH, p2WPKHVerifier);
    }



    public static ScriptVerificationStrategy getVerifier(ScriptType type) {
        if (!STRATEGIES.containsKey(type)) {
            throw new UnsupportedScriptException("不支持的脚本类型");
        }
        return STRATEGIES.get(type);
    }
}
