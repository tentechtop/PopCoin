package com.pop.popcoinsystem.service.strategy;

import com.pop.popcoinsystem.data.script.ScriptType;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
@Service
public class ScriptVerifierFactory {
    private static final Map<ScriptType, ScriptVerificationStrategy> STRATEGIES = new HashMap<>();
    static {
        STRATEGIES.put(ScriptType.P2PKH, new P2PKHVerifier());
        STRATEGIES.put(ScriptType.P2WPKH, new P2WPKHVerifier());
    }
    public static ScriptVerificationStrategy getVerifier(ScriptType type) {
        if (!STRATEGIES.containsKey(type)) {
            throw new UnsupportedScriptException("不支持的脚本类型");
        }
        return STRATEGIES.get(type);
    }
}
