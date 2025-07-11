package com.pop.popcoinsystem.network.common;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@Setter
public class FindNodeResult implements Serializable {

    private BigInteger destinationId;
    /* Closest nodes in the answer. */
    private List<NodeInfo> nodes;



    public FindNodeResult() {
        nodes = new ArrayList<>();
    }

    public FindNodeResult(BigInteger destinationId) {
        this();
        this.destinationId = destinationId;
    }

    public int size() {
        return nodes.size();
    }

    public void remove(int index) {
        nodes.remove(index);
    }

    public void add(NodeInfo externalNode) {
        nodes.add(externalNode);
    }

    public void update(List<NodeInfo> nodes){
        this.nodes = nodes;
    }



    public int merge(FindNodeResult findNodeAnswer, int findNodeSize) {
        int nbAdded = 0;

        for (NodeInfo c: findNodeAnswer.getNodes()) {
            if (!nodes.contains(c)) {
                nbAdded++;
                nodes.add(c);
            }
        }
        Collections.sort(nodes);
        //Trim the list
        while (findNodeAnswer.size() > findNodeSize) {
            findNodeAnswer.remove(findNodeAnswer.size() - 1);
        }
        return nbAdded;
    }





    /**
     * @return if the destination has been found
     */
    public boolean destinationFound() {
        if (nodes.size() < 1) {
            return false;
        }
        NodeInfo tail = nodes.get(0);
        return tail.getDistance().equals(0);
    }

    @Override
    public String toString() {
        return "Answer [destinationId=" + destinationId + ", nodes=" + nodes + "]";
    }

}
