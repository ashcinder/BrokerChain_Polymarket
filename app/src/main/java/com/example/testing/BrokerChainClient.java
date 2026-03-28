package com.example.testing;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;

/**
 * BrokerChainClient 客户端核心类
 * 作用：作为 DApp 与底层区块链服务端交互的桥梁。
 * 完整整合了网络请求(HTTP POST)、非对称加密(secp256k1)、哈希运算(SHA256/Keccak)以及各种业务请求的封装。
 */
public class BrokerChainClient {
    // 输出信息用
    private static final String TAG = "BrokerChainClient";

    // 统一配置服务器地址和端口，指向你们老师搭建的定制区块链节点
    private static final String BASE_URL = "https://dash.broker-chain.com:443/";

    // 全局默认合约地址（用于某些未显式传入目标地址的快捷接口）
    public static String contractaddr = "0xa5C9AA42021FfE5DDa9717BFC3707fe21076aAdf";

    // 引入 Gson 库，用于快速将 Java 的对象转化为 JSON 字符串，方便网络传输
    private static final Gson gson = new Gson();

    // =====================================================================
    // 1. 网络底层层 (封装原生 HTTPURLConnection)
    // 作用：负责将组装好的 JSON 数据通过 HTTP POST 方法发送到节点服务器
    // =====================================================================
    private static String doPost(String endpoint, Object requestBody) throws Exception {
        // 1. 将传入的请求对象（如 TxReq, CallReq）序列化为 JSON 字符串
        String jsonInputString = gson.toJson(requestBody);

        // 2. 拼接完整的请求 URL
        URL requestUrl = new URL(BASE_URL + endpoint);
        HttpURLConnection connection = (HttpURLConnection) requestUrl.openConnection();

        // 3. 设置 HTTP 请求头
        connection.setRequestMethod("POST"); // 区块链 RPC 请求通常都是 POST
        connection.setRequestProperty("Content-Type", "application/json; utf-8"); // 告诉服务器发的是 JSON
        connection.setRequestProperty("Accept", "application/json"); // 期望服务器返回 JSON
        connection.setDoOutput(true); // 允许向服务器写入请求体数据

        // 4. 将 JSON 字符串转换为 UTF-8 字节流并写入网络通道
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 5. 获取服务器的响应状态码 (如 200 代表成功，400/500 代表失败)
        int responseCode = connection.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            // 请求成功：读取 InputStream
            Scanner scanner = new Scanner(connection.getInputStream(), "UTF-8").useDelimiter("\\A");
            String res = scanner.hasNext() ? scanner.next() : "";
            Log.d(TAG, "Request to " + endpoint + " success: " + res);
            return res;
        } else {
            // 请求失败：读取 ErrorStream，捕获服务器抛出的异常信息（如 execution reverted）
            Scanner scanner = new Scanner(connection.getErrorStream(), "UTF-8").useDelimiter("\\A");
            String err = scanner.hasNext() ? scanner.next() : "";
            Log.e(TAG, "Request to " + endpoint + " failed: " + responseCode + " " + err);
            return err;
        }
    }

    // =====================================================================
    // 2. 加密安全层 (核心密码学)
    // 作用：处理区块链最底层的账号体系，包含私钥推导公钥、公钥推导地址、数据签名
    // =====================================================================

    /**
     * 根据以太坊标准，利用椭圆曲线算法 (secp256k1) 从私钥计算出公钥
     */
    public static String getPublicKeyFromPrivateKey(String privateKeyHex) {
        if (privateKeyHex.startsWith("0x")) privateKeyHex = privateKeyHex.substring(2);
        BigInteger privateKey = new BigInteger(privateKeyHex, 16);

        // 获取比特币和以太坊都在使用的 secp256k1 椭圆曲线参数
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");

        // 核心数学原理：公钥 = 曲线基点(G) 乘以 私钥(K)
        ECPoint publicPoint = spec.getG().multiply(privateKey);

        // 获取非压缩格式的公钥字节序列（以太坊标准）
        byte[] encoded = publicPoint.getEncoded(false);
        return Hex.toHexString(encoded);
    }

    /**
     * 对即将上链的数据进行 ECDSA (椭圆曲线数字签名算法) 签名
     * 作用：证明这笔交易确实是由持有该私钥的主人发起的，防止篡改。
     */
    public static String[] signECDSA(String privateKeyHex, String data) {
        if (privateKeyHex.startsWith("0x")) privateKeyHex = privateKeyHex.substring(2);
        BigInteger privateKey = new BigInteger(privateKeyHex, 16);

        // 初始化 ECDSA 签名器
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domainParameters = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN());
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ECPrivateKeyParameters(privateKey, domainParameters));

        // 步骤 1：先对原始字符串进行 SHA-256 哈希运算（签名通常是对哈希值进行签名，而不是原数据）
        SHA256Digest digest = new SHA256Digest();
        byte[] dataBytes = data.getBytes();
        digest.update(dataBytes, 0, dataBytes.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);

        // 步骤 2：对哈希值进行签名，生成 r 和 s 两个大整数（以太坊签名的核心组成部分）
        BigInteger[] rs = signer.generateSignature(hash);
        return new String[]{
                Hex.toHexString(rs[0].toByteArray()), // r 值
                Hex.toHexString(rs[1].toByteArray())  // s 值
        };
    }

    /**
     * 根据公钥推导钱包地址 (Address)
     * 规则：将公钥进行 Keccak-256 哈希，截取最后 20 个字节，加上 "0x" 前缀。
     */
    public static String getAddress(String privateKey) {
        try {
            String publicKey = getPublicKeyFromPrivateKey(privateKey);
            byte[] decode = Hex.decode(publicKey);

            KeccakDigest keccakDigest = new KeccakDigest(256);
            // 注意：以太坊公钥的第 1 个字节是标识符（通常是 0x04），哈希时需要将其丢弃
            keccakDigest.update(decode, 1, decode.length - 1);
            byte[] keccakHash = new byte[keccakDigest.getDigestSize()];
            keccakDigest.doFinal(keccakHash, 0);

            // 截取哈希值的最后 20 个字节作为地址
            byte[] addressBytes = new byte[20];
            System.arraycopy(keccakHash, keccakHash.length - 20, addressBytes, 0, 20);
            return "0x" + Hex.toHexString(addressBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // =====================================================================
    // 3. 业务接口层 (暴露给 DApp 前端直接调用的 API)
    // 作用：将不同的业务参数按老师后端的定制规则进行拼接 -> 签名 -> 封装请求 -> 发送
    // =====================================================================

    /**
     * 执行 eth_call (只读调用)
     * 作用：调用智能合约中被 view/pure 修饰的函数，不改变链上状态，不消耗 Gas。
     */
    public static String sendEthCall(String privateKey, String to, String data) throws Exception {
        String uuid = UUID.randomUUID().toString(); // 防重放攻击的随机字符串
        String value = "0x0";

        // 严格按照定制后端的规则拼接需要签名的字符串
        String thedata = to + data + value + uuid;
        String[] sign = signECDSA(privateKey, thedata);

        // 组装请求对象
        CallReq req = new CallReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setTo(to);
        req.setData(data);
        req.setValue(value);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);

        return doPost("eth_call", req);
    }

    /**
     * 执行 eth_sendTransaction (写入交易)
     * 作用：调用智能合约中会改变状态的函数（如 createGame, stakeTokens），需要消耗 Gas。
     */
    public static String sendEthTx(String privateKey, String to, String data, String value) throws Exception {
        // 设置 Gas Limit: 默认使用 5百万 (0x4c4b40)，保证携带长文本(如图片URL、背景介绍)上链时不会因 Gas 耗尽而 Revert
        String gas = "0x4c4b40";

        // 规范化 value 的十六进制格式
        String finalValue = (value == null || value.isEmpty() || value.equals("0")) ? "0x0" : value;
        if (!finalValue.startsWith("0x")) finalValue = "0x" + finalValue;

        String uuid = UUID.randomUUID().toString();
        // 写入交易的签名拼接规则包含 Gas
        String thedata = to + data + finalValue + gas + uuid;
        String[] sign = signECDSA(privateKey, thedata);

        SendETHTXReq req = new SendETHTXReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setTo(to);
        req.setData(data);
        req.setValue(finalValue);
        req.setGas(gas);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);

        return doPost("eth_sendTransaction", req);
    }

    // ---------- 以下为该区块链网络特定的专用快捷接口 ----------

    /**
     * 根据交易哈希查询交易的回执结果
     */
    public static String getTransactionReceipt(String hash, String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String thedata = uuid + hash;
        String[] sign = signECDSA(privateKey, thedata);

        GetTransactionReceiptReq req = new GetTransactionReceiptReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setUUID(hash);
        req.setRandomStr(uuid);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("eth_getTransactionReceipt", req);
    }

    public static String withdraw(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String[] sign = signECDSA(privateKey, uuid);
        WithdrawBrokerReq req = new WithdrawBrokerReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("withdrawbroker", req);
    }

    public static String stake(String privateKey, String value) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String data = uuid + value;
        String[] sign = signECDSA(privateKey, data);
        StakeReq req = new StakeReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setValue(value);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("stake", req);
    }

    public static String querybrokerprofit(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String[] sign = signECDSA(privateKey, uuid);
        ApplyBrokerReq req = new ApplyBrokerReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("querybrokerprofit", req);
    }

    public static String applybroker(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String[] sign = signECDSA(privateKey, uuid);
        ApplyBrokerReq req = new ApplyBrokerReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("applybroker", req);
    }

    public static String queryisbroker(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String[] sign = signECDSA(privateKey, uuid);
        QueryIsBrokerReq req = new QueryIsBrokerReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("queryisbroker", req);
    }

    /**
     * 查询指定钱包的以太币 (此网络中可能称为 BKC) 余额
     */
    public static ReturnAccountState getAddrAndBalance(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String rawAddress = getAddress(privateKey);
        // 此接口要求地址不带 0x 前缀
        String address = rawAddress.startsWith("0x") ? rawAddress.substring(2) : rawAddress;

        String data = uuid + address;
        String[] sign = signECDSA(privateKey, data);

        QueryReq queryReq = new QueryReq();
        queryReq.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        queryReq.setRandomStr(uuid);
        queryReq.setSign1(sign[0]);
        queryReq.setSign2(sign[1]);
        queryReq.setUUID(address);

        String result = doPost("query-g", queryReq);
        ReturnAccountState state = gson.fromJson(result, ReturnAccountState.class);

        // 换算单位：将链上返回的 Wei (10^18) 单位转换为 Ether (BKC) 方便前端显示
        if (state != null && state.getBalance() != null) {
            BigDecimal a = new BigDecimal(state.getBalance());
            BigDecimal b = new BigDecimal("1000000000000000000");
            state.setBalance(a.divide(b).toString());
        }
        return state;
    }

    public static String claim(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String[] sign = signECDSA(privateKey, uuid);
        ClaimReq req = new ClaimReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        return doPost("claim", req);
    }

    public static String SendTX(String privateKey, String to, String value, String fee) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String data = (fee != null && !fee.isEmpty()) ? uuid + to + value + fee : uuid + to + value;
        String[] sign = signECDSA(privateKey, data);

        TxReq req = new TxReq();
        req.setPublicKey(getPublicKeyFromPrivateKey(privateKey));
        req.setRandomStr(uuid);
        req.setTo(to);
        req.setValue(value);
        req.setSign1(sign[0]);
        req.setSign2(sign[1]);
        if (fee != null && !fee.isEmpty()) req.setFee(fee);

        return doPost("sendtx", req);
    }

    // =====================================================================
    // 4. 数据结构体层 (DTO)
    // 作用：这些内部类用作对象的承载体。Gson 会读取它们的字段名（或 @SerializedName 设定的名称）
    // 并将属性值映射成对应格式的 JSON 字符串，发送给节点服务器。
    // =====================================================================

    public static class CallReq {
        @SerializedName("PublicKey")
        private String PublicKey;
        @SerializedName("RandomStr")
        private String RandomStr;
        @SerializedName("To")
        private String To;
        @SerializedName("data")
        private String data;
        @SerializedName("value")
        private String value;
        @SerializedName("Sign1")
        private String Sign1;
        @SerializedName("Sign2")
        private String Sign2;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setTo(String t) {
            To = t;
        }

        public void setData(String d) {
            data = d;
        }

        public void setValue(String v) {
            value = v;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    public static class SendETHTXReq {
        private String PublicKey;
        private String RandomStr;
        private String To;
        @SerializedName("data")
        private String data;
        @SerializedName("value")
        private String value;
        private String Gas;
        private String Sign1;
        private String Sign2;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setTo(String t) {
            To = t;
        }

        public void setData(String d) {
            data = d;
        }

        public void setValue(String v) {
            value = v;
        }

        public void setGas(String g) {
            Gas = g;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    public static class GetTransactionReceiptReq {
        @SerializedName("uuid")
        private String UUID;
        @SerializedName("PublicKey")
        private String PublicKey;
        @SerializedName("RandomStr")
        private String RandomStr;
        @SerializedName("Sign1")
        private String Sign1;
        @SerializedName("Sign2")
        private String Sign2;

        public void setUUID(String u) {
            UUID = u;
        }

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    public static class WithdrawBrokerReq {
        private String PublicKey, RandomStr, Sign1, Sign2;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    public static class StakeReq {
        private String PublicKey, RandomStr, Sign1, Sign2, Value;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }

        public void setValue(String v) {
            Value = v;
        }
    }

    public static class ApplyBrokerReq {
        private String PublicKey, RandomStr, Sign1, Sign2;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    public static class QueryIsBrokerReq {
        private String PublicKey, RandomStr, Sign1, Sign2;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    public static class QueryReq {
        private String PublicKey, RandomStr, Sign1, Sign2, UUID;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }

        public void setUUID(String u) {
            UUID = u;
        }
    }

    public static class TxReq {
        private String PublicKey, RandomStr, To, Value, Sign1, Sign2, Fee;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setTo(String t) {
            To = t;
        }

        public void setValue(String v) {
            Value = v;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }

        public void setFee(String f) {
            Fee = f;
        }
    }

    public static class ClaimReq {
        private String PublicKey, RandomStr, Sign1, Sign2;

        public void setPublicKey(String p) {
            PublicKey = p;
        }

        public void setRandomStr(String r) {
            RandomStr = r;
        }

        public void setSign1(String s) {
            Sign1 = s;
        }

        public void setSign2(String s) {
            Sign2 = s;
        }
    }

    /**
     * 映射网络返回的账户状态数据 JSON
     */
    public static class ReturnAccountState {
        @SerializedName("account")
        private String AccountAddr;
        @SerializedName("balance")
        private String Balance;
        private boolean isHidden = false;
        private String accountName;
        private boolean isNewPrivateKeyFormat;

        public String getAccountAddr() {
            return AccountAddr;
        }

        public void setAccountAddr(String a) {
            AccountAddr = a;
        }

        public String getBalance() {
            return Balance;
        }

        public void setBalance(String b) {
            Balance = b;
        }
    }
}