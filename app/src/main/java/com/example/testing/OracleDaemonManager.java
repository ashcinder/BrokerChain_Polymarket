package com.example.testing;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

/**
 * 【预言机自动化守护进程管理器】
 * 优化版：只要外部数据源(JSON)公布了结果，无视游戏截止时间，立即提前触发清算开奖！
 */
public class OracleDaemonManager {

    private boolean isRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable daemonRunnable;

    // 记录正在清算的池子 ID，防止网络延迟导致的重复广播
    private final List<Integer> processingGameIds = new ArrayList<>();

    private final Web3Repository repository;
    private final DataProvider dataProvider;
    private final OracleCallback callback;

    // ================= 接口定义 =================

    public interface DataProvider {
        List<Web3Repository.GameModel> getLatestGames();
    }

    public interface OracleCallback {
        void onLogAppended(String msg);
        void onStatusChanged(boolean isRunning);
        void onResolveSuccess();
    }

    // ================= 初始化与控制 =================

    public OracleDaemonManager(Web3Repository repository, DataProvider dataProvider, OracleCallback callback) {
        this.repository = repository;
        this.dataProvider = dataProvider;
        this.callback = callback;
    }

    public void toggleDaemon() {
        isRunning = !isRunning;
        callback.onStatusChanged(isRunning);

        if (isRunning) {
            callback.onLogAppended("[Engine] 守护进程已启动，开始轮询外部数据源...");
            startDaemon();
        } else {
            callback.onLogAppended("[Engine] 守护进程已安全停止。");
            if (daemonRunnable != null) {
                handler.removeCallbacks(daemonRunnable);
            }
        }
    }

    public void destroy() {
        isRunning = false;
        if (daemonRunnable != null) {
            handler.removeCallbacks(daemonRunnable);
        }
    }

    // ================= 核心自动化流程 =================

    private void startDaemon() {
        daemonRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                List<Web3Repository.GameModel> games = dataProvider.getLatestGames();

                // 将网络请求放入后台网络线程，防止卡死主界面
                AppExecutors.getInstance().networkIO().execute(() -> {
                    boolean foundMatch = false;

                    try {
                        // 1. 爬虫：直接去互联网上拉取最新的全局赛果 JSON
                        // ⚠️ 极其重要：请替换为你自己的 GitHub Gist 或 JSONBin 链接 (记得加 ?meta=false)
                        String apiUrl = "https://gist.githubusercontent.com/ashcinder/d1f2b30accebd0b021c09f7df5023d89/raw/2e99feca4aaa2cbeccdc8fcf7b5166ed370f8141/gistfile1.txt";

                        URL url = new URL(apiUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);

                        if (conn.getResponseCode() == 200) {
                            InputStream is = conn.getInputStream();
                            Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A");
                            String jsonStr = scanner.hasNext() ? scanner.next() : "";
                            JSONObject resultJson = new JSONObject(jsonStr);

                            // 2. 遍历大盘中所有【未开奖】且【未流局退款】的市场 (注意：已经彻底去掉了 deadlineSec 限制！)
                            if (games != null) {
                                for (Web3Repository.GameModel game : games) {
                                    if (!game.isResolved && !game.isRefunded && !processingGameIds.contains(game.id)) {
                                        String gameIdKey = String.valueOf(game.id - 1);

                                        // 3. 对比环节：只要我们爬取的 JSON 里有这个博弈池的结果，立刻触发开奖！
                                        if (resultJson.has(gameIdKey)) {
                                            int winningIndex = resultJson.getInt(gameIdKey);

                                            // 安全校验：防止 JSON 填错导致越界崩溃
                                            if (winningIndex >= 0 && winningIndex < game.optionCount) {
                                                foundMatch = true;
                                                // 切回主线程，开始进行后续的 VDF 计算和上链流程
                                                AppExecutors.getInstance().mainThread().execute(() -> {
                                                    executeAutoResolve(game, winningIndex);
                                                });
                                                break; // 每次轮询只处理 1 个，处理完马上跳出，防止并发发送拥堵
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            throw new Exception("HTTP 状态码异常: " + conn.getResponseCode());
                        }

                        // 如果扫了一圈，发现网上的 JSON 里没有任何未开奖池子的结果
                        if (!foundMatch) {
                            AppExecutors.getInstance().mainThread().execute(() -> {
                                callback.onLogAppended("[Scan] 轮询完成，暂未发现可清算的市场结果...");
                            });
                        }

                    } catch (Exception e) {
                        AppExecutors.getInstance().mainThread().execute(() -> {
                            callback.onLogAppended("[Spider Error] 抓取外部数据失败: " + e.getMessage());
                        });
                    } finally {
                        // 无论本轮是成功还是失败报错，10秒后都必须继续执行下一次轮询（死循环心跳机制）
                        if (isRunning) {
                            handler.postDelayed(daemonRunnable, 10000);
                        }
                    }
                });
            }
        };

        // 立即触发第一次轮询
        handler.post(daemonRunnable);
    }

    /**
     * 执行全自动化清算流程 (算 VDF -> 广播上链)
     */
    private void executeAutoResolve(Web3Repository.GameModel game, int winningOption) {
        processingGameIds.add(game.id); // 锁定该 ID，防止在区块打包期间被重复轮询处理

        callback.onLogAppended("----------------------------------");
        callback.onLogAppended("[Action] 捕获到外部开奖信号，目标 ID: " + game.id + " (" + game.desc + ")");

        String winnerName = game.optionNames.get(winningOption);
        callback.onLogAppended("[Match] 获取到最终胜出方为: [" + winnerName + "]");
        callback.onLogAppended("[VDF] 开始进行零知识证明(哈希碰撞)计算...");

        // 将繁重的加密计算任务丢给 CPU 计算专用线程
        AppExecutors.getInstance().computeIO().execute(() -> {
            try {
                // 执行 VDF 证明计算 (模拟 50 万次 SHA-256 碰撞)
                String seed = "BrokerChain_" + game.id + "_" + System.currentTimeMillis();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = seed.getBytes();
                for (int i = 0; i < 500_000; i++) hash = digest.digest(hash);
                byte[] finalHash = hash;

                // 计算完毕，切回主线程准备发送区块链交易
                AppExecutors.getInstance().mainThread().execute(() -> {
                    callback.onLogAppended("[VDF] 证明计算完毕，准备构造以太坊交易...");

                    Function f = new Function("resolveGame", Arrays.asList(
                            new Uint256(game.id), new Uint8(winningOption), new Bytes32(finalHash)
                    ), Collections.emptyList());

                    repository.sendTransaction(BigInteger.ZERO, f, "自动化清算成功", new Web3Repository.TxCallback() {
                        @Override
                        public void onTxSent(String txHash) {
                            callback.onLogAppended("[Tx] 交易已广播，等待矿工区块确认...");
                        }

                        @Override
                        public void onConfirmed(String message) {
                            callback.onLogAppended("[Success] 提前清算完毕！ID:" + game.id + " 开奖上链成功！");
                            processingGameIds.remove(Integer.valueOf(game.id));
                            callback.onResolveSuccess(); // 通知 UI 层刷新全局大盘
                        }

                        @Override
                        public void onError(String error) {
                            callback.onLogAppended("[Error] 上链失败: " + error);
                            processingGameIds.remove(Integer.valueOf(game.id)); // 失败后解锁，以便下次轮询重试
                        }
                    });
                });
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> {
                    callback.onLogAppended("[System Error] VDF 计算异常: " + e.getMessage());
                    processingGameIds.remove(Integer.valueOf(game.id));
                });
            }
        });
    }
}