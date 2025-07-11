package com.pop.popcoinsystem.network.common;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 这段代码实现了一个 Kademlia DHT 网络中的节点查找结果缩减器（Reducer）。它的核心功能是从一个较大的候选节点列表中筛选出最相关的节点集合，以满足特定的查找需求。
 */
public class FindNodeResultReducer {

    private final BigInteger nodeId;
    private final FindNodeResult findNodeResult;
    private final int max;
    private final int identifierSize;

    public FindNodeResultReducer(BigInteger nodeId, FindNodeResult findNodeResult, int max, int identifierSize) {
        this.nodeId = nodeId;
        this.findNodeResult = findNodeResult;
        this.max = max;
        this.identifierSize = identifierSize;
    }


    public void reduce(){
        List<NodeInfo> nodes = new ArrayList<>();
        List<NodeInfo> answerNodes = this.findNodeResult.getNodes();

        for(int i = 0; i < identifierSize; i++){
            if (nodes.size() <= this.max){
                break;
            }

            for (NodeInfo answerNode : answerNodes) {
                if (answerNode.getId().equals(power(nodeId, i))){
                    nodes.add(answerNode);
                    answerNodes.remove(answerNode);
                    break;
                }
            }
        }

        int i = 0;
        while (nodes.size() <= this.max && i < answerNodes.size()){
            nodes.add(answerNodes.get(i));
            i++;
        }


        this.findNodeResult.update(nodes);
    }

    @SuppressWarnings("unchecked")
    private BigInteger power(BigInteger in, int power){
        return  in.pow(power);
    }

}
