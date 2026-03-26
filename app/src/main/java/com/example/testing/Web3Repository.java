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

public class Web3Repository {
    private static final String TAG = "Web3Repository";

    // ⚠️ 极其重要：请替换为你刚部署的支持头像和介绍的最新合约地址！
    private static final String CONTRACT_ADDRESS = "0xa5C9AA42021FfE5DDa9717BFC3707fe21076aAdf";

    private final Credentials credentials;
    private final Web3j web3j;

    public Web3Repository(String privateKey) {
        this.credentials = Credentials.create(privateKey);
        this.web3j = Web3j.build(new HttpService("https://dash.broker-chain.com:443"));
    }

    public String getWalletAddress() {
        return credentials.getAddress();
    }

    public interface DataCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public interface TxCallback {
        void onTxSent(String txHash);
        void onConfirmed(String message);
        void onError(String error);
    }

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

    private String ethCall(Function function) throws Exception {
        String data = FunctionEncoder.encode(function);
        String cleanPrivateKey = credentials.getEcKeyPair().getPrivateKey().toString(16);
        String response = BrokerChainClient.sendEthCall(cleanPrivateKey, CONTRACT_ADDRESS, data);
        return extractHexResult(response);
    }

    public void sendTransaction(BigInteger value, Function function, String successMsg, TxCallback callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String data = FunctionEncoder.encode(function);
                String cleanPrivateKey = credentials.getEcKeyPair().getPrivateKey().toString(16);
                String valueHex = value.compareTo(BigInteger.ZERO) > 0 ? value.toString(16) : "0x0";

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

    public void getOracleAddress(DataCallback<String> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                Function f = new Function("officialOracle", Collections.emptyList(), Collections.singletonList(new TypeReference<Address>() {}));
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

    @SuppressWarnings("unchecked")
    public void getGames(DataCallback<List<GameModel>> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                Function fCount = new Function("gameCount", Collections.emptyList(), Collections.singletonList(new TypeReference<Uint256>() {}));
                String countHex = ethCall(fCount);
                if (countHex == null || countHex.equals("0x")) {
                    AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(new ArrayList<>()));
                    return;
                }
                int count = ((Uint256) FunctionReturnDecoder.decode(countHex, fCount.getOutputParameters()).get(0)).getValue().intValue();

                List<GameModel> list = new ArrayList<>();

                for (int i = 1; i <= count; i++) {
                    try {
                        Function fInfo = new Function("getGameInfo", Collections.singletonList(new Uint256(i)),
                                Arrays.asList(
                                        new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {},
                                        new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {},
                                        new TypeReference<DynamicArray<Utf8String>>() {}, new TypeReference<Uint8>() {},
                                        new TypeReference<Uint256>() {}, new TypeReference<Bool>() {},
                                        new TypeReference<Uint8>() {}, new TypeReference<Uint256>() {},
                                        new TypeReference<Bool>() {}
                                ));

                        String infoHex = ethCall(fInfo);
                        if (infoHex == null || infoHex.equals("0x")) continue;
                        List<Type> res = FunctionReturnDecoder.decode(infoHex, fInfo.getOutputParameters());
                        if (res.isEmpty()) continue;

                        GameModel model = new GameModel();
                        model.id = i;
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
                                Arrays.asList(new Uint256(i), new Address(credentials.getAddress())),
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
                    } catch (Exception gameEx) {
                        Log.e(TAG, "解析博弈池 " + i + " 时出现异常，已跳过", gameEx);
                    }
                }
                Collections.reverse(list);
                AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(list));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    public void getGameDetail(int id, DataCallback<GameModel> callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                Function fInfo = new Function("getGameInfo", Collections.singletonList(new Uint256(id)),
                        Arrays.asList(
                                new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {},
                                new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {},
                                new TypeReference<DynamicArray<Utf8String>>() {}, new TypeReference<Uint8>() {},
                                new TypeReference<Uint256>() {}, new TypeReference<Bool>() {},
                                new TypeReference<Uint8>() {}, new TypeReference<Uint256>() {},
                                new TypeReference<Bool>() {}
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

    public static class GameModel {
        public int id;
        public String desc;
        public String condition;
        public String avatarUrl;
        public String detailedInfo;
        public List<String> optionNames;
        public int optionCount;
        public BigInteger totalPool;
        public boolean isResolved;
        public int winningOption;
        public long deadlineSec;
        public boolean isRefunded;
        public List<BigInteger> optionPools;
        public List<BigInteger> myStakes;
    }
}