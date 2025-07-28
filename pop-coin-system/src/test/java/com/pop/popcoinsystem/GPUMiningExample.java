package com.pop.popcoinsystem;

import org.jocl.*;
import static org.jocl.CL.*;

public class GPUMiningExample {
    public static void main(String[] args) {
        // 设置JCuda参数
        System.setProperty("org.jocl.enableGlDebugInfo", "true");
        System.setProperty("jocl.useNativePaths", "true");

        // 初始化OpenCL环境
        cl_platform_id platform = getFirstGPUPlatform();
        cl_device_id device = getFirstDevice(platform);
        cl_context context = createContext(platform, device);
        cl_command_queue commandQueue = createCommandQueue(context, device);

        // 准备挖矿数据
        int workItemCount = 1024;
        int[] inputData = new int[workItemCount];
        for (int i = 0; i < workItemCount; i++) {
            inputData[i] = i;
        }
        int[] outputData = new int[workItemCount];

        // 创建OpenCL内存对象
        cl_mem inputBuffer = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int * workItemCount, Pointer.to(inputData), null);
        cl_mem outputBuffer = clCreateBuffer(context,
                CL_MEM_WRITE_ONLY,
                Sizeof.cl_int * workItemCount, null, null);

        // 创建并编译OpenCL程序
        String kernelSource =
                "__kernel void mine(__global const int* input, __global int* output) {\n" +
                        "    int id = get_global_id(0);\n" +
                        "    // 简单的挖矿模拟：寻找能被1000整除的数值\n" +
                        "    if (input[id] % 1000 == 0) {\n" +
                        "        output[id] = input[id] * 2;\n" +
                        "    } else {\n" +
                        "        output[id] = 0;\n" +
                        "    }\n" +
                        "}";
        cl_program program = clCreateProgramWithSource(context, 1,
                new String[]{kernelSource}, null, null);
        clBuildProgram(program, 0, null, null, null, null);

        // 创建并执行OpenCL内核
        cl_kernel kernel = clCreateKernel(program, "mine", null);
        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(inputBuffer));
        clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(outputBuffer));

        long globalWorkSize[] = new long[]{workItemCount};
        clEnqueueNDRangeKernel(commandQueue, kernel, 1, null,
                globalWorkSize, null, 0, null, null);

        // 读取结果
        clEnqueueReadBuffer(commandQueue, outputBuffer, CL_TRUE, 0,
                workItemCount * Sizeof.cl_int, Pointer.to(outputData),
                0, null, null);

        // 输出挖矿结果
        System.out.println("挖矿结果：");
        for (int i = 0; i < workItemCount; i++) {
            if (outputData[i] != 0) {
                System.out.printf("找到区块 #%d，奖励: %d\n", i, outputData[i]);
            }
        }

        // 释放资源
        clReleaseKernel(kernel);
        clReleaseProgram(program);
        clReleaseMemObject(inputBuffer);
        clReleaseMemObject(outputBuffer);
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);
    }

    private static cl_platform_id getFirstGPUPlatform() {
        int numPlatformsArray[] = new int[1];
        clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];
        cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
        clGetPlatformIDs(numPlatforms, platforms, null);

        for (cl_platform_id platform : platforms) {
            cl_device_id devices[] = new cl_device_id[1];
            int numDevicesArray[] = new int[1];
            clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
            if (numDevicesArray[0] > 0) {
                return platform;
            }
        }
        throw new RuntimeException("未找到GPU平台");
    }

    private static cl_device_id getFirstDevice(cl_platform_id platform) {
        cl_device_id devices[] = new cl_device_id[1];
        clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, devices, null);
        return devices[0];
    }

    private static cl_context createContext(cl_platform_id platform, cl_device_id device) {
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);
        return clCreateContext(contextProperties, 1, new cl_device_id[]{device}, null, null, null);
    }

    private static cl_command_queue createCommandQueue(cl_context context, cl_device_id device) {
        return clCreateCommandQueue(context, device, CL_QUEUE_PROFILING_ENABLE, null);
    }
}