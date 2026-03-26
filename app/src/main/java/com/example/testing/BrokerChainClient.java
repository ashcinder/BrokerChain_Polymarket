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
 * 完整整合了老师端 HTTPUtil, SecurityUtil, MyUtil 的所有功能
 */
public class BrokerChainClient {
    private static final String TAG = "BrokerChainClient";
    // 统一配置服务器地址和端口
    private static final String BASE_URL = "https://dash.broker-chain.com:443/";
    // 全局合约地址（用于部分快捷接口的默认 To 地址）
    public static String contractaddr = "0x9aeA171ea0e05DEd44D14ee87632648b0812B906";

    private static final Gson gson = new Gson();

    // =====================================================================
    // 1. 网络底层层 (复刻 HTTPUtil)
    // =====================================================================
    private static String doPost(String endpoint, Object requestBody) throws Exception {
        String jsonInputString = gson.toJson(requestBody);
        URL requestUrl = new URL(BASE_URL + endpoint);
        HttpURLConnection connection = (HttpURLConnection) requestUrl.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            Scanner scanner = new Scanner(connection.getInputStream(), "UTF-8").useDelimiter("\\A");
            String res = scanner.hasNext() ? scanner.next() : "";
            Log.d(TAG, "Request to " + endpoint + " success: " + res);
            return res;
        } else {
            Scanner scanner = new Scanner(connection.getErrorStream(), "UTF-8").useDelimiter("\\A");
            String err = scanner.hasNext() ? scanner.next() : "";
            Log.e(TAG, "Request to " + endpoint + " failed: " + responseCode + " " + err);
            return err;
        }
    }

    // =====================================================================
    // 2. 加密安全层 (复刻 SecurityUtil)
    // =====================================================================
    public static String getPublicKeyFromPrivateKey(String privateKeyHex) {
        if (privateKeyHex.startsWith("0x")) privateKeyHex = privateKeyHex.substring(2);
        BigInteger privateKey = new BigInteger(privateKeyHex, 16);
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECPoint publicPoint = spec.getG().multiply(privateKey);
        byte[] encoded = publicPoint.getEncoded(false); // 以太坊标准非压缩格式
        return Hex.toHexString(encoded);
    }

    public static String[] signECDSA(String privateKeyHex, String data) {
        if (privateKeyHex.startsWith("0x")) privateKeyHex = privateKeyHex.substring(2);
        BigInteger privateKey = new BigInteger(privateKeyHex, 16);
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domainParameters = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN());
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ECPrivateKeyParameters(privateKey, domainParameters));

        SHA256Digest digest = new SHA256Digest();
        byte[] dataBytes = data.getBytes();
        digest.update(dataBytes, 0, dataBytes.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);

        BigInteger[] rs = signer.generateSignature(hash);
        return new String[]{
                Hex.toHexString(rs[0].toByteArray()),
                Hex.toHexString(rs[1].toByteArray())
        };
    }

    public static String getAddress(String privateKey) {
        try {
            String publicKey = getPublicKeyFromPrivateKey(privateKey);
            byte[] decode = Hex.decode(publicKey);
            KeccakDigest keccakDigest = new KeccakDigest(256);
            keccakDigest.update(decode, 1, decode.length - 1);
            byte[] keccakHash = new byte[keccakDigest.getDigestSize()];
            keccakDigest.doFinal(keccakHash, 0);
            byte[] addressBytes = new byte[20];
            System.arraycopy(keccakHash, keccakHash.length - 20, addressBytes, 0, 20);
            return "0x" + Hex.toHexString(addressBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // =====================================================================
    // 3. 业务接口层 (复刻 MyUtil 中暴露的所有 API)
    // =====================================================================

    // 原生万能接口（Web3Repository 当前依赖的接口）
    public static String sendEthCall(String privateKey, String to, String data) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String value = "0x0";
        String thedata = to + data + value + uuid;
        String[] sign = signECDSA(privateKey, thedata);

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

    public static String sendEthTx(String privateKey, String to, String data, String value) throws Exception {
        String gas = "0xf4240";
        String finalValue = (value == null || value.isEmpty() || value.equals("0")) ? "0x0" : value;
        if (!finalValue.startsWith("0x")) finalValue = "0x" + finalValue;
        String uuid = UUID.randomUUID().toString();
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

    // 以下是老师 MyUtil 里的特定快捷接口
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

    public static ReturnAccountState getAddrAndBalance(String privateKey) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String rawAddress = getAddress(privateKey);
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
        if (state != null && state.getBalance() != null) {
            BigDecimal a = new BigDecimal(state.getBalance());
            BigDecimal b = new BigDecimal("1000000000000000000"); // 转换为 Ether
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
    // 4. 数据结构体层 (复刻 MyUtil 里的内部类)
    // =====================================================================

    public static class CallReq {
        @SerializedName("PublicKey") private String PublicKey;
        @SerializedName("RandomStr") private String RandomStr;
        @SerializedName("To") private String To;
        @SerializedName("data") private String data;
        @SerializedName("value") private String value;
        @SerializedName("Sign1") private String Sign1;
        @SerializedName("Sign2") private String Sign2;
        // setters
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setTo(String t) { To = t; }
        public void setData(String d) { data = d; }
        public void setValue(String v) { value = v; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class SendETHTXReq {
        private String PublicKey;
        private String RandomStr;
        private String To;
        @SerializedName("data") private String data;
        @SerializedName("value") private String value;
        private String Gas;
        private String Sign1;
        private String Sign2;
        // setters
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setTo(String t) { To = t; }
        public void setData(String d) { data = d; }
        public void setValue(String v) { value = v; }
        public void setGas(String g) { Gas = g; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class GetTransactionReceiptReq {
        @SerializedName("uuid") private String UUID;
        @SerializedName("PublicKey") private String PublicKey;
        @SerializedName("RandomStr") private String RandomStr;
        @SerializedName("Sign1") private String Sign1;
        @SerializedName("Sign2") private String Sign2;
        public void setUUID(String u) { UUID = u; }
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class WithdrawBrokerReq {
        private String PublicKey, RandomStr, Sign1, Sign2;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class StakeReq {
        private String PublicKey, RandomStr, Sign1, Sign2, Value;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
        public void setValue(String v) { Value = v; }
    }

    public static class ApplyBrokerReq {
        private String PublicKey, RandomStr, Sign1, Sign2;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class QueryIsBrokerReq {
        private String PublicKey, RandomStr, Sign1, Sign2;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class QueryReq {
        private String PublicKey, RandomStr, Sign1, Sign2, UUID;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
        public void setUUID(String u) { UUID = u; }
    }

    public static class TxReq {
        private String PublicKey, RandomStr, To, Value, Sign1, Sign2, Fee;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setTo(String t) { To = t; }
        public void setValue(String v) { Value = v; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
        public void setFee(String f) { Fee = f; }
    }

    public static class ClaimReq {
        private String PublicKey, RandomStr, Sign1, Sign2;
        public void setPublicKey(String p) { PublicKey = p; }
        public void setRandomStr(String r) { RandomStr = r; }
        public void setSign1(String s) { Sign1 = s; }
        public void setSign2(String s) { Sign2 = s; }
    }

    public static class ReturnAccountState {
        @SerializedName("account") private String AccountAddr;
        @SerializedName("balance") private String Balance;
        private boolean isHidden = false;
        private String accountName;
        private boolean isNewPrivateKeyFormat;
        public String getAccountAddr() { return AccountAddr; }
        public void setAccountAddr(String a) { AccountAddr = a; }
        public String getBalance() { return Balance; }
        public void setBalance(String b) { Balance = b; }
    }
}