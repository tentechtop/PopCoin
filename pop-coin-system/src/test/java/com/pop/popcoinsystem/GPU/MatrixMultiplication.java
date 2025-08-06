package com.pop.popcoinsystem.GPU;

import jcuda.*;
import jcuda.runtime.*;


import static jcuda.runtime.cudaMemcpyKind.cudaMemcpyDeviceToHost;
import static jcuda.runtime.cudaMemcpyKind.cudaMemcpyHostToDevice;

public class MatrixMultiplication {
    public static void main(String[] args) {
        JCuda.setExceptionsEnabled(true);
        int N = 2;
        float[] hostA = {1, 2, 3, 4};
        float[] hostB = {5, 6, 7, 8};
        float[] hostC = new float[N * N];
        Pointer d_A = new Pointer();
        Pointer d_B = new Pointer();
        Pointer d_C = new Pointer();
        JCuda.cudaMalloc(d_A, hostA.length * Sizeof.FLOAT);
        JCuda.cudaMalloc(d_B, hostB.length * Sizeof.FLOAT);
        JCuda.cudaMalloc(d_C, hostC.length * Sizeof.FLOAT);
        JCuda.cudaMemcpy(d_A, Pointer.to(hostA), hostA.length * Sizeof.FLOAT, cudaMemcpyHostToDevice);
        JCuda.cudaMemcpy(d_B, Pointer.to(hostB), hostB.length * Sizeof.FLOAT, cudaMemcpyHostToDevice);
        float alpha = 1;
        float beta = 0;

        JCuda.cudaMemcpy(Pointer.to(hostC), d_C, hostC.length * Sizeof.FLOAT, cudaMemcpyDeviceToHost);
        System.out.println("Result C:");
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                System.out.print(hostC[i * N + j] + " ");
            }
            System.out.println();
        }
        JCuda.cudaFree(d_A);
        JCuda.cudaFree(d_B);
        JCuda.cudaFree(d_C);
    }
}