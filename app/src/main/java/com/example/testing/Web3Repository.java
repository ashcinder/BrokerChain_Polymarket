package com.example.testing;

import android.util.Log;

import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 【区块链数据仓库层】
 * 作用：运用了 Repository 经典架构设计，它将复杂的底层区块链 ABI（应用程序二进制接口）十六进制编码操作封装起来。
 * 界面 Activity 不应直接向网络发送请求，而是向该类发送高级指令，获取干净的对象模型数据。
 */
public class Web3Repository {
    private static final String TAG = "Web3Repository";

    // ⚠️ 极其重要：当前在网络上负责处理 PredictionMarket 逻辑的主智能合约的内存地址
    private static final String CONTRACT_ADDRESS = "0x9123eeF54ED3AC1C0FA0D610e1E44c83D05f76E2";

    // 核心对象：钱包身份凭证，包含由私钥推导出来的以太坊账户地址
    private final Credentials credentials;
    // 工业标准的 Java 以太坊操作库 Web3j 实例
    private final Web3j web3j;

    public Web3Repository(String privateKey) {
        this.credentials = Credentials.create(privateKey);
        this.web3j = Web3j.build(new HttpService("https://dash.broker-chain.com:443"));
    }

    public String getWalletAddress() {
        return credentials.getAddress();
    }

    // ================= 数据回调接口定义 =================

    // 用于返回普通查询对象的结果给 UI 线程
    public interface DataCallback<T> {
        void onSuccess(T result);

        void onError(String error);
    }

    // 专用于区块链写入交易：有发出的瞬时回声 (Hash) 以及区块确认的回声 (Confirmed)
    public interface TxCallback {
        void onTxSent(String txHash);

        void onConfirmed(String message);

        void onError(String error);
    }

    /**
     * 智能扒取节点返回 JSON 数据里的实际 Hex 值
     */
    private String extractHexResult(String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) return "0x";
        try {
            if (responseJson.trim().startsWith("{")) {
                JSONObject obj = new JSONObject(responseJson);
                String res = "0x";
                if (obj.has("result")) {
                    res = obj.getString("result");
                } else if (obj.has("data")) {
                    res = obj.getString("data");
                }
                // 抓取到 reverted 则拦截，这是底层虚拟机的标准拦截词
                if (res.toLowerCase().contains("reverted") || res.toLowerCase().contains("error")) {
                    return "0x";
                }
                return res;
            }
            if (responseJson.toLowerCase().contains("reverted")) return "0x";
            return responseJson.trim();
        } catch (Exception e) {
            return "0x";
        }
    }

    /**
     * 封装一次以太坊 Call 行为 (不会改变世界状态的节点读操作)
     */
    private String ethCall(Function function) throws Exception {
        // 利用 Web3j 将方法名和数据打包成 EVM 可以理解的 Bytecode
        String data = FunctionEncoder.encode(function);
        String cleanPrivateKey = credentials.getEcKeyPair().getPrivateKey().toString(16);
        // 向私有 RPC 节点调用 POST 请求
        String response = BrokerChainClient.sendEthCall(cleanPrivateKey, CONTRACT_ADDRESS, data);
        return extractHexResult(response);
    }

    /**
     * 封装一次以太坊 Transaction 行为 (写入操作，需消耗手续费)
     */
    public void sendTransaction(BigInteger value, Function function, String successMsg, TxCallback callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String data = FunctionEncoder.encode(function);
                String cleanPrivateKey = credentials.getEcKeyPair().getPrivateKey().toString(16);

                // 将金额 (msg.value) 也转化为无 0x 前缀的标准格式
                String valueHex = value.compareTo(BigInteger.ZERO) > 0 ? value.toString(16) : "0x0";

                // 开始执行扣费上链动作
                String response = BrokerChainClient.sendEthTx(cleanPrivateKey, CONTRACT_ADDRESS, data, valueHex);

                if (response == null || response.toLowerCase().contains("error") || response.toLowerCase().contains("failed")) {
                    postError(callback, "交易失败: " + response);
                } else {
                    AppExecutors.getInstance().mainThread().execute(() -> {
                        callback.onTxSent("Transaction Sent");
                        callback.onConfirmed(successMsg);
                    });
                }
            } catch (Exception e) {
                postError(callback, "交易异常: " + e.getMessage());
            }
        });
    }

    /**
     * 特殊接口：合约部署指令（将 to 指向空地址）
     */
    public void deployContract(String bytecode, TxCallback callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String cleanPrivateKey = credentials.getEcKeyPair().getPrivateKey().toString(16);
                String response = BrokerChainClient.sendEthTx(cleanPrivateKey, "", bytecode, "0x0");

                if (response == null || response.toLowerCase().contains("error")) {
                    postError(callback, "部署失败: " + response);
                } else {
                    AppExecutors.getInstance().mainThread().execute(() -> {
                        callback.onTxSent("Deployment Sent");
                        callback.onConfirmed("部署已提交");
                    });
                }
            } catch (Exception e) {
                postError(callback, "部署异常: " + e.getMessage());
            }
        });
    }

    /**
     * 获取合约内定义的预言机(Oracle)管理者账户地址，用于比对当前使用者的权限
     */
    public void getOracleAddress(DataCallback<String> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // 对应 Solidity 代码里的 `address public officialOracle;` 会隐式生成的同名查询函数
                Function f = new Function("officialOracle", Collections.emptyList(), Collections.singletonList(new TypeReference<Address>() {
                }));
                String resHex = ethCall(f);
                if (resHex != null && !resHex.equals("0x")) {
                    List<Type> res = FunctionReturnDecoder.decode(resHex, f.getOutputParameters());
                    if (!res.isEmpty()) {
                        AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(((Address) res.get(0)).getValue()));
                        return;
                    }
                }
                postError(callback, "获取预言机地址失败");
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 查询以太坊主网虚拟币余额
     */
    public void getBalance(DataCallback<BigDecimal> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String cleanPrivateKey = credentials.getEcKeyPair().getPrivateKey().toString(16);
                BrokerChainClient.ReturnAccountState state = BrokerChainClient.getAddrAndBalance(cleanPrivateKey);

                if (state != null && state.getBalance() != null) {
                    BigDecimal balance = new BigDecimal(state.getBalance());
                    AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(balance));
                } else {
                    postError(callback, "未能获取到余额数据");
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 【极其关键的性能优化接口】获取博弈池大盘数据 (加入截断加载机制)
     */
    @SuppressWarnings("unchecked")
    public void getGames(DataCallback<List<GameModel>> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // 第一步：获取当前总共有多少个游戏池
                Function fCount = new Function("gameCount", Collections.emptyList(), Collections.singletonList(new TypeReference<Uint256>() {}));
                String countHex = ethCall(fCount);
                if (countHex == null || countHex.equals("0x")) {
                    AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(new ArrayList<>()));
                    return;
                }

                int totalCount = ((Uint256) FunctionReturnDecoder.decode(countHex, fCount.getOutputParameters()).get(0)).getValue().intValue();

                // 🌟 核心优化 2：数据截断。只拉取最新发行的 10 个池子，拒绝全量拉取引发的网络拥堵
                int startIndex = Math.max(1, totalCount - 9);
                int fetchCount = totalCount - startIndex + 1;

                List<GameModel> list = Collections.synchronizedList(new ArrayList<>());
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(fetchCount);

                // 第二步：并发拉取最新的几个池子
                for (int i = startIndex; i <= totalCount; i++) {
                    final int gameId = i;
                    AppExecutors.getInstance().networkIO().execute(() -> {
                        try {
                            Function fInfo = new Function("getGameInfo", Collections.singletonList(new Uint256(gameId)),
                                    Arrays.asList(
                                            new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {},
                                            new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {},
                                            new TypeReference<DynamicArray<Utf8String>>() {}, new TypeReference<Uint8>() {},
                                            new TypeReference<Uint256>() {}, new TypeReference<Bool>() {},
                                            new TypeReference<Uint8>() {}, new TypeReference<Uint256>() {},
                                            new TypeReference<Bool>() {}
                                    ));

                            String infoHex = ethCall(fInfo);
                            if (infoHex != null && !infoHex.equals("0x")) {
                                List<Type> res = FunctionReturnDecoder.decode(infoHex, fInfo.getOutputParameters());
                                if (!res.isEmpty()) {
                                    GameModel model = new GameModel();
                                    model.id = gameId;
                                    model.desc = ((Utf8String) res.get(0)).getValue();
                                    model.condition = ((Utf8String) res.get(1)).getValue();
                                    model.avatarUrl = ((Utf8String) res.get(2)).getValue();
                                    model.detailedInfo = ((Utf8String) res.get(3)).getValue();

                                    List<Utf8String> namesList = ((DynamicArray<Utf8String>) res.get(4)).getValue();
                                    model.optionNames = new ArrayList<>();
                                    for (Utf8String u : namesList) model.optionNames.add(u.getValue());

                                    model.optionCount = ((Uint8) res.get(5)).getValue().intValue();
                                    model.totalPool = ((Uint256) res.get(6)).getValue();
                                    model.isResolved = ((Bool) res.get(7)).getValue();
                                    model.winningOption = ((Uint8) res.get(8)).getValue().intValue();
                                    model.deadlineSec = ((Uint256) res.get(9)).getValue().longValue();
                                    model.isRefunded = ((Bool) res.get(10)).getValue();

                                    Function fExtra = new Function("getGameExtraData",
                                            Arrays.asList(new Uint256(gameId), new Address(credentials.getAddress())),
                                            Arrays.asList(
                                                    new TypeReference<DynamicArray<Uint256>>() {},
                                                    new TypeReference<DynamicArray<Uint256>>() {}
                                            ));

                                    String extraHex = ethCall(fExtra);
                                    List<Type> extraRes = FunctionReturnDecoder.decode(extraHex, fExtra.getOutputParameters());

                                    List<Uint256> poolsArray = ((DynamicArray<Uint256>) extraRes.get(0)).getValue();
                                    List<Uint256> stakesArray = ((DynamicArray<Uint256>) extraRes.get(1)).getValue();

                                    model.optionPools = new ArrayList<>();
                                    model.myStakes = new ArrayList<>();

                                    for (int opt = 0; opt < model.optionCount; opt++) {
                                        model.optionPools.add(poolsArray.get(opt).getValue());
                                        model.myStakes.add(stakesArray.get(opt).getValue());
                                    }
                                    list.add(model);
                                }
                            }
                        } catch (Exception gameEx) {
                            Log.e(TAG, "解析博弈池 " + gameId + " 时出现异常", gameEx);
                        } finally {
                            latch.countDown(); // 保证无论成功失败都能释放锁
                        }
                    });
                }

                latch.await(); // 阻塞等待这一批次的数据全部加载完
                Collections.reverse(list); // 翻转使得最新的在最上方
                AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(list));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 单独获取某指定 ID 博弈池的信息，代码逻辑跟上文循环的内部完全一致。
     * 用于详情页 (GameDetailActivity) 的极速局部刷新。
     */
    @SuppressWarnings("unchecked")
    public void getGameDetail(int id, DataCallback<GameModel> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                Function fInfo = new Function("getGameInfo", Collections.singletonList(new Uint256(id)),
                        Arrays.asList(
                                new TypeReference<Utf8String>() {
                                }, new TypeReference<Utf8String>() {
                                },
                                new TypeReference<Utf8String>() {
                                }, new TypeReference<Utf8String>() {
                                },
                                new TypeReference<DynamicArray<Utf8String>>() {
                                }, new TypeReference<Uint8>() {
                                },
                                new TypeReference<Uint256>() {
                                }, new TypeReference<Bool>() {
                                },
                                new TypeReference<Uint8>() {
                                }, new TypeReference<Uint256>() {
                                },
                                new TypeReference<Bool>() {
                                }
                        ));

                String infoHex = ethCall(fInfo);
                if (infoHex == null || infoHex.equals("0x")) {
                    postError(callback, "获取详情失败");
                    return;
                }
                List<Type> res = FunctionReturnDecoder.decode(infoHex, fInfo.getOutputParameters());
                if (res.isEmpty()) {
                    postError(callback, "数据解析为空");
                    return;
                }

                GameModel model = new GameModel();
                model.id = id;
                model.desc = ((Utf8String) res.get(0)).getValue();
                model.condition = ((Utf8String) res.get(1)).getValue();
                model.avatarUrl = ((Utf8String) res.get(2)).getValue();
                model.detailedInfo = ((Utf8String) res.get(3)).getValue();

                List<Utf8String> namesList = ((DynamicArray<Utf8String>) res.get(4)).getValue();
                model.optionNames = new ArrayList<>();
                for (Utf8String u : namesList) model.optionNames.add(u.getValue());

                model.optionCount = ((Uint8) res.get(5)).getValue().intValue();
                model.totalPool = ((Uint256) res.get(6)).getValue();
                model.isResolved = ((Bool) res.get(7)).getValue();
                model.winningOption = ((Uint8) res.get(8)).getValue().intValue();
                model.deadlineSec = ((Uint256) res.get(9)).getValue().longValue();
                model.isRefunded = ((Bool) res.get(10)).getValue();

                Function fExtra = new Function("getGameExtraData",
                        Arrays.asList(new Uint256(id), new Address(credentials.getAddress())),
                        Arrays.asList(
                                new TypeReference<DynamicArray<Uint256>>() {
                                },
                                new TypeReference<DynamicArray<Uint256>>() {
                                }
                        ));

                String extraHex = ethCall(fExtra);
                List<Type> extraRes = FunctionReturnDecoder.decode(extraHex, fExtra.getOutputParameters());

                List<Uint256> poolsArray = ((DynamicArray<Uint256>) extraRes.get(0)).getValue();
                List<Uint256> stakesArray = ((DynamicArray<Uint256>) extraRes.get(1)).getValue();

                model.optionPools = new ArrayList<>();
                model.myStakes = new ArrayList<>();

                for (int opt = 0; opt < model.optionCount; opt++) {
                    model.optionPools.add(poolsArray.get(opt).getValue());
                    model.myStakes.add(stakesArray.get(opt).getValue());
                }

                AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(model));
            } catch (Exception e) {
                postError(callback, "获取详情异常: " + e.getMessage());
            }
        });
    }

    private void postError(TxCallback callback, String error) {
        AppExecutors.getInstance().mainThread().execute(() -> callback.onError(error));
    }

    private <T> void postError(DataCallback<T> callback, String error) {
        AppExecutors.getInstance().mainThread().execute(() -> callback.onError(error));
    }

    /**
     * 【内部的数据转换承载体 - DTO 模型】
     * 作用：把底层零散的以太坊强类型参数，转化为前端好处理的高级 Java 对象。
     */
    public static class GameModel {
        public int id;                    // 唯一识别编号
        public String desc;               // 用户发布的主题名字
        public String condition;          // 开奖结果依附的条件准则
        public String avatarUrl;          // 储存在图床服务器里的封面直链
        public String detailedInfo;       // 超长篇幅的市场背景叙述
        public List<String> optionNames;  // 例如 ["Yes", "No"]
        public int optionCount;           // 提供了几个可以买入下注的方向
        public BigInteger totalPool;      // 整个市场共计吸收的资金总量
        public boolean isResolved;        // 控制器：判断是否已经被人执行了开奖清算动作
        public int winningOption;         // 最后真正赢家的下注索引
        public long deadlineSec;          // 用户在创建时设定的终局倒计时 (这里单位是毫秒)
        public boolean isRefunded;        // 退款状态
        public List<BigInteger> optionPools; // 【核心数据】按比例存放所有人在该分类下的买入全款
        public List<BigInteger> myStakes;    // 【鉴权展示】当前登录的使用者的下注留档轨迹
    }
}