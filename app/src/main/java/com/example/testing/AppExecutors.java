package com.example.testing;

// Android 主线程相关类

import android.os.Handler;
import android.os.Looper;

// Java 并发工具
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AppExecutors
 * -----------------------------------
 * 一个线程调度管理类（线程池封装）
 * <p>
 * 作用：
 * 统一管理 App 中不同类型的线程：
 * 1. 网络线程（networkIO）
 * 2. 计算线程（computeIO）
 * 3. 主线程（mainThread）
 * <p>
 * 设计模式：
 * - 单例模式（Singleton）
 */
public class AppExecutors {

    // volatile 保证多线程可见性（防止指令重排序）
    private static volatile AppExecutors instance;

    // 网络请求线程池（适合 IO 密集型任务）
    private final Executor networkIO;

    // 计算线程（单线程，避免并发冲突）
    private final Executor computeIO;

    // 主线程执行器（UI 线程）
    private final Executor mainThread;

    /**
     * 私有构造函数（单例模式）
     */
    private AppExecutors() {
        // 固定线程池：最多 4 个线程同时执行
        networkIO = Executors.newFixedThreadPool(4);

        // 单线程执行器（串行执行任务）
        computeIO = Executors.newSingleThreadExecutor();

        // 主线程执行器（用于 UI 更新）
        mainThread = new MainThreadExecutor();
    }

    /**
     * 获取单例实例（双重检查锁）
     */
    public static AppExecutors getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) instance = new AppExecutors();
            }
        }
        return instance;
    }

    /**
     * 获取网络线程池
     */
    public Executor networkIO() {
        return networkIO;
    }

    /**
     * 获取计算线程
     */
    public Executor computeIO() {
        return computeIO;
    }

    /**
     * 获取主线程执行器
     */
    public Executor mainThread() {
        return mainThread;
    }

    /**
     * 主线程执行器
     * -----------------------------------
     * 原理：
     * Handler + Looper.getMainLooper()
     * 把任务切换到 UI 线程执行
     */
    private static class MainThreadExecutor implements Executor {

        // 绑定主线程的 Handler
        private final Handler mainThreadHandler =
                new Handler(Looper.getMainLooper());

        /**
         * 执行任务（Runnable）
         * 会被切换到主线程执行
         */
        @Override
        public void execute(Runnable command) {
            mainThreadHandler.post(command);
        }
    }
}