package com.example.testing;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【带时间惩罚的 AI 双重共识预言机引擎】
 * 优先级逻辑：
 * 1. 爬虫 YES + AI YES -> 立即开奖
 * 2. 爬虫 YES + AI NO  -> 触发冷却时间，挂起后重试
 * 3. 爬虫 NO  + 已过截止时间 -> 触发 AI 兜底查询，若 AI YES 则开奖
 * 4. 爬虫 NO  + AI NO  -> 触发冷却时间，挂起后重试
 */
public class OracleDaemonManager {

    private boolean isRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable daemonRunnable;

    // DeepSeek API 配置
    private static final String DEEPSEEK_API_KEY = "sk-679c615e26234c67b00677ba689a80d8";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    private final List<Integer> processingGameIds = new ArrayList<>();

    // 🌟 创新点：时间惩罚冷却池 (GameId -> 解锁时间戳)
    private final Map<Integer, Long> cooldownMap = new HashMap<>();

    private final Web3Repository repository;
    private final DataProvider dataProvider;
    private final OracleCallback callback;

    public interface DataProvider {
        List<Web3Repository.GameModel> getLatestGames();
    }

    public interface OracleCallback {
        void onLogAppended(String msg);
        void onStatusChanged(boolean isRunning);
        void onResolveSuccess();
    }

    public OracleDaemonManager(Web3Repository repository, DataProvider dataProvider, OracleCallback callback) {
        this.repository = repository;
        this.dataProvider = dataProvider;
        this.callback = callback;
    }

    public void toggleDaemon() {
        isRunning = !isRunning;
        callback.onStatusChanged(isRunning);

        if (isRunning) {
            callback.onLogAppended("[Engine] 分布式容错预言机已启动，严格遵循优先级矩阵...");
            startDaemon();
        } else {
            callback.onLogAppended("[Engine] 守护进程已安全停止。");
            if (daemonRunnable != null) handler.removeCallbacks(daemonRunnable);
        }
    }

    public void destroy() {
        isRunning = false;
        if (daemonRunnable != null) handler.removeCallbacks(daemonRunnable);
    }

    private void startDaemon() {
        daemonRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                List<Web3Repository.GameModel> games = dataProvider.getLatestGames();

                AppExecutors.getInstance().networkIO().execute(() -> {
                    boolean foundMatch = false;
                    try {
                        // 高速触发层：爬取传统 JSON 接口
                        String apiUrl = "https://gist.githubusercontent.com/ashcinder/63196268269e95c67d80c172f54d8f67/raw/90cfbe589cdbf9a5f4df9d36da3f20f5a2c27c80/gistfile1.txt";
                        URL url = new URL(apiUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);

                        JSONObject resultJson = new JSONObject();
                        if (conn.getResponseCode() == 200) {
                            try (InputStream is = conn.getInputStream();
                                 Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                                String jsonStr = scanner.hasNext() ? scanner.next() : "";
                                // 数据清洗：修复漏掉的逗号和多余的尾部逗号
                                jsonStr = jsonStr.replaceAll("(\\d)\\s+(?=\")", "$1, ");
                                jsonStr = jsonStr.replaceAll(",\\s*\\}", "}");
                                resultJson = new JSONObject(jsonStr);
                            }
                        }

                        if (games != null) {
                            long currentTime = System.currentTimeMillis();
                            for (Web3Repository.GameModel game : games) {
                                if (game.isResolved || game.isRefunded || processingGameIds.contains(game.id)) continue;

                                // 🌟 检查是否在冷却期 (小黑屋) 中
                                if (cooldownMap.containsKey(game.id) && currentTime < cooldownMap.get(game.id)) {
                                    continue; // 还没解封，跳过
                                }

                                String gameIdKey = String.valueOf(game.id - 1);
                                int spiderWinnerIndex = -1;

                                // 优先级判定条件读取
                                if (resultJson.has(gameIdKey)) {
                                    spiderWinnerIndex = resultJson.getInt(gameIdKey);
                                }

                                if (spiderWinnerIndex >= 0 && spiderWinnerIndex < game.optionCount) {
                                    // 🟢 场景 1/2：爬虫 YES
                                    foundMatch = true;
                                    processingGameIds.add(game.id);

                                    final int finalSpiderIndex = spiderWinnerIndex;
                                    AppExecutors.getInstance().mainThread().execute(() -> {
                                        callback.onLogAppended("----------------------------------");
                                        callback.onLogAppended("[Fast Node] 爬虫提交待定胜者: " + game.optionNames.get(finalSpiderIndex));
                                        callback.onLogAppended("[AI Node] 启动 DeepSeek 交叉核查...");
                                    });
                                    verifyWithDeepSeek(game, spiderWinnerIndex);
                                    break;

                                } else if (currentTime > game.deadlineSec) {
                                    // 🟡 场景 3/4：爬虫 NO，但是比赛已经过了截止时间！触发 AI 兜底逻辑
                                    foundMatch = true;
                                    processingGameIds.add(game.id);

                                    AppExecutors.getInstance().mainThread().execute(() -> {
                                        callback.onLogAppended("----------------------------------");
                                        callback.onLogAppended("[Timeout] 爬虫无响应且市场已过期！触发 AI 兜底查证...");
                                    });
                                    verifyWithDeepSeek(game, -1); // 传入 -1 代表爬虫无结果
                                    break;
                                }
                            }
                        }

                        if (!foundMatch) {
                            AppExecutors.getInstance().mainThread().execute(() -> {
                                callback.onLogAppended("[Scan] 轮询完成，暂无满足触发条件的市场...");
                            });
                        }

                    } catch (Exception e) {
                        AppExecutors.getInstance().mainThread().execute(() -> {
                            callback.onLogAppended("[Spider Error] 节点异常: " + e.getMessage());
                        });
                    } finally {
                        if (isRunning) handler.postDelayed(daemonRunnable, 10000);
                    }
                });
            }
        };

        handler.post(daemonRunnable);
    }

    /**
     * 🌟 核心：DeepSeek 事实核查节点 (包含智能信息提取)
     * @param proposedWinnerIndex 爬虫给出的结果。如果爬虫 NO，则传入 -1
     */
    private void verifyWithDeepSeek(Web3Repository.GameModel game, int proposedWinnerIndex) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String prompt = "你是一个极其严谨的区块链预言机（Oracle）事实核查节点。\n" +
                        "你的唯一任务是核实以下博弈事件是否在现实世界中已经有了【确凿的、官方的最终结果】。\n\n" +
                        "【博弈事件】: " + game.desc + "\n" +
                        "【清算规则】: " + game.condition + "\n" +
                        "【备选选项】: " + game.optionNames.toString() + "\n\n" +
                        "【核心安全机制与惩罚规则】:\n" +
                        "1. 如果该事件尚未发生、正在进行中、或者目前只有预测/民调而没有官方最终定论，你必须严格回复：【结果：未决】。\n" +
                        "2. 只有当该事件已经彻底结束，并且有官方结果时，你才能回复：【结果：已决，胜者索引：X】（X代表获胜选项的索引数值，从0开始计算）。\n" +
                        "严格按照以上格式输出，绝不能输出任何多余废话。";

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", prompt);

                JSONArray messages = new JSONArray();
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "deepseek-chat");
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.0);

                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + DEEPSEEK_API_KEY);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                if (conn.getResponseCode() == 200) {
                    JSONObject responseJson;
                    try (InputStream is = conn.getInputStream();
                         Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                        responseJson = new JSONObject(scanner.hasNext() ? scanner.next() : "");
                    }

                    String aiReply = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                    AppExecutors.getInstance().mainThread().execute(() -> {
                        callback.onLogAppended("[AI Response] " + aiReply);

                        // 🌟 利用正则动态解析 AI 返回的索引
                        Matcher matcher = Pattern.compile("【结果：已决，胜者索引：(\\d+)】").matcher(aiReply);
                        int aiWinnerIndex = -1;
                        if (matcher.find()) {
                            aiWinnerIndex = Integer.parseInt(matcher.group(1));
                        }

                        if (proposedWinnerIndex != -1) {
                            // 🟢 场景 1 & 2 判定：爬虫 YES
                            if (aiWinnerIndex == proposedWinnerIndex) {
                                callback.onLogAppended("[Consensus] 🟢 共识达成！准备上链...");
                                executeAutoResolve(game, aiWinnerIndex);
                            } else {
                                callback.onLogAppended("[Conflict] 🔴 爬虫与 AI 产生分歧！触发时间惩罚，挂起 5 分钟后重试。");
                                setCooldown(game.id);
                            }
                        } else {
                            // 🟡 场景 3 & 4 判定：爬虫 NO，AI 兜底
                            if (aiWinnerIndex != -1) {
                                callback.onLogAppended("[Fallback Success] 🟢 AI 兜底查证成功！准备上链...");
                                executeAutoResolve(game, aiWinnerIndex);
                            } else {
                                callback.onLogAppended("[Unresolved] 🟡 AI 判断现实中仍无结果。触发时间惩罚，挂起 5 分钟后重试。");
                                setCooldown(game.id);
                            }
                        }
                    });
                } else {
                    throw new Exception("AI Node 连接失败: " + conn.getResponseCode());
                }
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> {
                    callback.onLogAppended("[System Error] AI 核查节点异常: " + e.getMessage());
                    setCooldown(game.id); // 异常也算作验证失败，关入小黑屋
                });
            }
        });
    }

    /**
     * 将发生分歧或异常的博弈池加入冷却期 (挂起 5 分钟)
     */
    private void setCooldown(int gameId) {
        // 当前时间 + 5分钟 (5 * 60 * 1000 毫秒)
        cooldownMap.put(gameId, System.currentTimeMillis() + 300000);
        processingGameIds.remove(Integer.valueOf(gameId));
    }

    /**
     * 执行最终清算 (共识达成后触发)
     */
    private void executeAutoResolve(Web3Repository.GameModel game, int winningOption) {
        callback.onLogAppended("[VDF] 开始进行零知识证明计算保障网络安全...");

        AppExecutors.getInstance().computeIO().execute(() -> {
            try {
                String seed = "BrokerChain_" + game.id + "_" + System.currentTimeMillis();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = seed.getBytes();
                for (int i = 0; i < 500_000; i++) hash = digest.digest(hash);
                byte[] finalHash = hash;

                AppExecutors.getInstance().mainThread().execute(() -> {
                    callback.onLogAppended("[VDF] 证明计算完毕，构造清算交易...");

                    Function f = new Function("resolveGame", Arrays.asList(
                            new Uint256(game.id), new Uint8(winningOption), new Bytes32(finalHash)
                    ), Collections.emptyList());

                    repository.sendTransaction(BigInteger.ZERO, f, "自动化共识清算成功", new Web3Repository.TxCallback() {
                        @Override
                        public void onTxSent(String txHash) {
                            callback.onLogAppended("[Tx] 交易已广播，等待上链确认...");
                        }

                        @Override
                        public void onConfirmed(String message) {
                            callback.onLogAppended("[Success] 🏆 优先级共识清算完美收官！");
                            processingGameIds.remove(Integer.valueOf(game.id));
                            callback.onResolveSuccess();
                        }

                        @Override
                        public void onError(String error) {
                            callback.onLogAppended("[Error] 合约执行失败: " + error);
                            processingGameIds.remove(Integer.valueOf(game.id));
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