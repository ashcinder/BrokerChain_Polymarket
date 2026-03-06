package com.example.testing;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.*;
import java.security.MessageDigest;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BrokerChainWallet";

    // studio访问brokerchain本地节点
    private static final String BROKER_CHAIN_URL = "http://10.0.2.2:42645";

    // 私钥
    private static final String PRIVATE_KEY = "ad5799695148adb16b3a31ef150ccaea7f9b4ed8308dceaa66e2e9a6e4133dbb";

    // 合约地址
    private static final String CONTRACT_ADDRESS = "0xA33910138F5F2483AAd1fDb5AB7729001e68CB0c";

    // oracle地址
    private static final String OFFICIAL_ORACLE_ADDRESS = "0xf672b5e97e653798b448920555bffbd67ded6534";

    private Web3j web3j;
    private Credentials credentials;
    private TextView tvLog, tvWalletBalance, tvWalletAddress, tvLobbyGames, tvMyPositions;
    private EditText etGameDesc, etStakeGameId, etStakeAmount, etResolveGameId, etClaimGameId;
    private RadioGroup rgStakeOption, rgResolveOption;

    // activity 入口方法
    @Override
    protected void onCreate(Bundle savedInstanceState) { //Activity入口方法
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();

        web3j = Web3j.build(new HttpService(BROKER_CHAIN_URL));
        credentials = Credentials.create(PRIVATE_KEY);

        log("📱 系统启动。当前身份: " + credentials.getAddress().substring(0, 10) + "...");

        findViewById(R.id.btn_refresh_all).setOnClickListener(v -> syncData());
        findViewById(R.id.btn_create_game).setOnClickListener(v -> createGame());
        findViewById(R.id.btn_stake).setOnClickListener(v -> stakeTokens());
        findViewById(R.id.btn_resolve).setOnClickListener(v -> resolveGame());
        findViewById(R.id.btn_claim).setOnClickListener(v -> claimReward());

        syncData();
        runDiagnosticCheck();
    }

    // 初始化UI界面
    private void initUI() {
        tvLog = findViewById(R.id.tv_log);
        tvWalletBalance = findViewById(R.id.tv_wallet_balance);
        tvWalletAddress = findViewById(R.id.tv_wallet_address);
        tvLobbyGames = findViewById(R.id.tv_lobby_games);
        tvMyPositions = findViewById(R.id.tv_my_positions);
        etGameDesc = findViewById(R.id.et_game_desc);
        etStakeGameId = findViewById(R.id.et_stake_game_id);
        etStakeAmount = findViewById(R.id.et_stake_amount);
        rgStakeOption = findViewById(R.id.rg_stake_option);
        etResolveGameId = findViewById(R.id.et_resolve_game_id);
        rgResolveOption = findViewById(R.id.rg_resolve_option);
        etClaimGameId = findViewById(R.id.et_claim_game_id);
    }

    // 初始化时检测
    private void runDiagnosticCheck() {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                logSafe("\n====== 🚨 系统底层诊断开始 ======");

                BigInteger oracleBalance = web3j.ethGetBalance(OFFICIAL_ORACLE_ADDRESS, DefaultBlockParameterName.LATEST).send().getBalance();
                if (oracleBalance.compareTo(BigInteger.ZERO) == 0) {
                    logSafe("❌ 致命错误：预言机没钱支付 Gas 费！");
                } else {
                    logSafe("✅ 预言机资金充足。");
                }

                Function fOracle = new Function("officialOracle", Collections.emptyList(), Collections.singletonList(new TypeReference<Address>() {}));
                String oracleHex = web3j.ethCall(org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                        credentials.getAddress(), null, null, null, CONTRACT_ADDRESS, BigInteger.ZERO,
                        FunctionEncoder.encode(fOracle)), DefaultBlockParameterName.LATEST).send().getValue();

                List<Type> res = FunctionReturnDecoder.decode(oracleHex, fOracle.getOutputParameters());
                if (!res.isEmpty()) {
                    String actualOracle = ((Address)res.get(0)).getValue();
                    if (!actualOracle.equalsIgnoreCase(OFFICIAL_ORACLE_ADDRESS)) {
                        logSafe("❌ 致命错误：合约法官是 [" + actualOracle + "]，与代码不匹配！");
                    } else {
                        logSafe("✅ 合约法官地址匹配。");
                    }
                }
                logSafe("====== 诊断结束 ======\n");
            } catch (Exception e) {
                Log.e(TAG, "Diagnostic Error", e);
                logSafe("⚠️ 诊断失败(网络未连接): " + e.getMessage());
            }
        }).start();
    }

    // 专为 BrokerChain 定制的节点代签模式 (原样保留)
    private void sendTx(BigInteger value, String data, String successMsg) {
        new Thread(() -> {
            try {
                org.web3j.protocol.core.methods.request.Transaction tx =
                        org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                                credentials.getAddress(),
                                null,
                                null,
                                BigInteger.valueOf(3000000),
                                CONTRACT_ADDRESS,
                                value,
                                data);

                EthSendTransaction response = web3j.ethSendTransaction(tx).send();

                if (response.hasError()) {
                    logSafe("❌ 节点拒绝: " + response.getError().getMessage());
                    return;
                }

                final String txHash = response.getTransactionHash();
                if (txHash == null || txHash.isEmpty()) {
                    logSafe("⚠️ 交易未产生 Hash");
                    return;
                }

                logSafe("⏳ 交易已发给节点，等待 PBFT 共识...\nHash: " + txHash.substring(0, 15) + "...");

                boolean confirmed = false;
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(2000);
                    org.web3j.protocol.core.methods.response.EthGetTransactionReceipt receipt =
                            web3j.ethGetTransactionReceipt(txHash).send();

                    if (receipt.getTransactionReceipt().isPresent()) {
                        String status = receipt.getTransactionReceipt().get().getStatus();
                        if ("0x1".equalsIgnoreCase(status) || "0x01".equalsIgnoreCase(status)) {
                            logSafe("🎉 " + successMsg);
                            confirmed = true;
                            break;
                        } else {
                            logSafe("❌ 执行失败 (EVM Reverted)");
                            return;
                        }
                    }
                }
                if (!confirmed) logSafe("⏰ 确认超时");
                Thread.sleep(1000);
                syncData();
            } catch (Exception e) {
                Log.e(TAG, "Send TX Error", e);
                logSafe("❌ 交易异常: " + e.getMessage());
            }
        }).start();
    }

    // ================= 【纯净新增：VDF 延迟计算引擎】 =================
    private void computeVDFAndResolve(int gameId, int option, String seed) {
        logSafe("\n⚙️ [VDF 引擎] 启动！开始进行可验证延迟计算...");

        new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                // 难度系数：50 万次连续哈希运算
                int difficulty = 500_000;

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = seed.getBytes();

                for (int i = 0; i < difficulty; i++) {
                    hash = digest.digest(hash);
                    // 在 UI 打印进度条
                    if (i % 100000 == 0 && i > 0) {
                        int progress = (int) (((double) i / difficulty) * 100);
                        logSafe("⏳ [VDF 引擎] 哈希计算中... " + progress + "%");
                    }
                }

                long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
                String vdfProofHex = Numeric.toHexString(hash);

                logSafe("✅ [VDF 引擎] 计算完成！耗时: " + timeTaken + " 秒");
                logSafe("🔑 延迟证明: " + vdfProofHex.substring(0, 15) + "...");

                // 将 VDF 结果提交上链
                submitResolveWithVDF(gameId, option, hash);

            } catch (Exception e) {
                logSafe("❌ VDF 计算失败: " + e.getMessage());
            }
        }).start();
    }

    private void submitResolveWithVDF(int id, int opt, byte[] vdfProof) {
        logSafe("📡 正在携带 VDF 证明发布开奖结果...");
        try {
            // 参数中加入了 VDF 的 bytes32 类型
            Function f = new Function("resolveGame",
                    Arrays.asList(new Uint256(id), new Uint8(opt), new Bytes32(vdfProof)),
                    Collections.emptyList());
            sendTx(BigInteger.ZERO, FunctionEncoder.encode(f), "结果宣布成功，状态已更新！");
        } catch (Exception e) {
            logSafe("❌ 打包异常: " + e.getMessage());
        }
    }
    // =================================================================

    // 【修改：将 resolveGame 对接到 VDF 引擎】
    private void resolveGame() {
        if (etResolveGameId.getText().toString().isEmpty()) return;
        int id = Integer.parseInt(etResolveGameId.getText().toString());
        int opt = (rgResolveOption.getCheckedRadioButtonId() == R.id.rb_resolve_op1) ? 1 : 2;

        // 生成不可预测的随机种子
        String vdfSeed = "BrokerChain_Game_" + id + "_" + System.currentTimeMillis();
        // 触发 VDF 延迟
        computeVDFAndResolve(id, opt, vdfSeed);
    }

    private void syncData() {
        new Thread(() -> {
            try {
                BigInteger balance = web3j.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
                BigDecimal bkc = org.web3j.utils.Convert.fromWei(new BigDecimal(balance), org.web3j.utils.Convert.Unit.ETHER);
                runOnUiThread(() -> {
                    tvWalletBalance.setText("余额: " + bkc.setScale(4, RoundingMode.HALF_UP) + " BKC");
                    tvWalletAddress.setText("地址: " + credentials.getAddress());
                });
                refreshLobbyAndPositionsSafe();
            } catch (Exception e) {
                Log.e(TAG, "Sync Error", e);
                logSafe("❌ 同步异常: " + e.getMessage());
            }
        }).start();
    }

    private void refreshLobbyAndPositionsSafe() {
        try {
            Function fCount = new Function("gameCount", Collections.emptyList(), Collections.singletonList(new TypeReference<Uint256>() {}));
            String countHex = web3j.ethCall(org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(credentials.getAddress(), null, null, null, CONTRACT_ADDRESS, BigInteger.ZERO, FunctionEncoder.encode(fCount)), DefaultBlockParameterName.LATEST).send().getValue();
            if (countHex == null || countHex.equals("0x")) return;
            int count = ((Uint256) FunctionReturnDecoder.decode(countHex, fCount.getOutputParameters()).get(0)).getValue().intValue();

            StringBuilder lobby = new StringBuilder();
            StringBuilder pos = new StringBuilder();

            for (int i = 1; i <= count; i++) {
                Function fGame = new Function("games", Collections.singletonList(new Uint256(i)),
                        Arrays.asList(new TypeReference<Uint256>(){}, new TypeReference<Address>(){}, new TypeReference<Utf8String>(){},
                                new TypeReference<Uint256>(){}, new TypeReference<Uint256>(){}, new TypeReference<Bool>(){}, new TypeReference<Uint8>(){}, new TypeReference<Uint256>(){}));
                String gameHex = web3j.ethCall(org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(credentials.getAddress(), null, null, null, CONTRACT_ADDRESS, BigInteger.ZERO, FunctionEncoder.encode(fGame)), DefaultBlockParameterName.LATEST).send().getValue();
                List<Type> res = FunctionReturnDecoder.decode(gameHex, fGame.getOutputParameters());
                if (res.isEmpty()) continue;

                String desc = ((Utf8String)res.get(2)).getValue();
                BigInteger p1 = ((Uint256)res.get(3)).getValue();
                BigInteger p2 = ((Uint256)res.get(4)).getValue();
                boolean isRes = ((Bool)res.get(5)).getValue();

                lobby.append("ID: ").append(i).append(" ").append(desc).append("\n")
                        .append(isRes ? "【已开奖🔒】" : "【进行中🟢】")
                        .append(" ").append(calculateOdds(p1, p2)).append("\n")
                        .append("池1: ").append(formatWei(p1)).append(" | 池2: ").append(formatWei(p2))
                        .append("\n---\n");

                BigInteger s1 = getStake(i, 1);
                BigInteger s2 = getStake(i, 2);
                if (s1.signum() > 0 || s2.signum() > 0) {
                    pos.append("博弈 #").append(i).append(": ")
                            .append(s1.signum() > 0 ? "押1(" + formatWei(s1) + ") " : "")
                            .append(s2.signum() > 0 ? "押2(" + formatWei(s2) + ") " : "")
                            .append("\n");
                }
            }

            runOnUiThread(() -> {
                tvLobbyGames.setText(lobby.length() > 0 ? lobby.toString() : "暂无博弈局");
                tvMyPositions.setText(pos.length() > 0 ? pos.toString() : "暂无个人持仓");
            });
        } catch (Exception e) {
            Log.e(TAG, "Refresh Error", e);
            logSafe("⚠️ 刷新大厅失败: " + e.getMessage());
        }
    }

    private String calculateOdds(BigInteger p1, BigInteger p2) {
        if (p1.add(p2).signum() == 0) return "(赔率 1:1)";
        BigDecimal b1 = new BigDecimal(p1);
        BigDecimal b2 = new BigDecimal(p2);
        BigDecimal total = b1.add(b2);
        String r1 = b1.signum() > 0 ? total.divide(b1, 2, RoundingMode.HALF_UP).toString() : "∞";
        String r2 = b2.signum() > 0 ? total.divide(b2, 2, RoundingMode.HALF_UP).toString() : "∞";
        return "赔率 (1赔" + r1 + " / 2赔" + r2 + ")";
    }

    private String formatWei(BigInteger wei) {
        return org.web3j.utils.Convert.fromWei(new BigDecimal(wei), org.web3j.utils.Convert.Unit.ETHER)
                .setScale(2, RoundingMode.HALF_UP).toString() + " BKC";
    }

    private BigInteger getStake(int id, int opt) throws Exception {
        Function f = new Function("stakes",
                Arrays.asList(new Uint256(id), new Address(credentials.getAddress()), new Uint8(opt)),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        String resHex = web3j.ethCall(org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(), null, null, null, CONTRACT_ADDRESS, BigInteger.ZERO,
                FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send().getValue();
        List<Type> res = FunctionReturnDecoder.decode(resHex, f.getOutputParameters());
        return res.isEmpty() ? BigInteger.ZERO : ((Uint256) res.get(0)).getValue();
    }

    private void createGame() {
        logSafe("📡 准备创建博弈池...");
        Function f = new Function("createGame", Collections.singletonList(new Utf8String(etGameDesc.getText().toString())), Collections.emptyList());
        sendTx(BigInteger.ZERO, FunctionEncoder.encode(f), "创建博弈成功");
    }

    private void stakeTokens() {
        logSafe("📡 准备质押...");
        try {
            int id = Integer.parseInt(etStakeGameId.getText().toString());
            BigDecimal amount = new BigDecimal(etStakeAmount.getText().toString());
            BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();
            int opt = (rgStakeOption.getCheckedRadioButtonId() == R.id.rb_stake_op1) ? 1 : 2;
            Function f = new Function("stakeTokens", Arrays.asList(new Uint256(id), new Uint8(opt)), Collections.emptyList());
            sendTx(wei, FunctionEncoder.encode(f), "质押成功");
        } catch (NumberFormatException e) {
            logSafe("❌ 输入金额或 ID 格式错误");
        }
    }

    private void claimReward() {
        logSafe("📡 准备提现清算...");
        try {
            int id = Integer.parseInt(etClaimGameId.getText().toString());
            Function f = new Function("claimReward", Collections.singletonList(new Uint256(id)), Collections.emptyList());
            sendTx(BigInteger.ZERO, FunctionEncoder.encode(f), "清算成功");
        } catch (NumberFormatException e) {
            logSafe("❌ 输入的清算 ID 错误");
        }
    }

    private void logSafe(String m) {
        runOnUiThread(() -> log(m));
    }

    // (原样保留，安全滚动逻辑)
    private void log(String m) {
        tvLog.append("\n> " + m);
        ViewParent cp = tvLog.getParent();
        while (cp != null && !(cp instanceof ScrollView)) {
            cp = cp.getParent();
        }
        if (cp instanceof ScrollView) {
            final ScrollView sv = (ScrollView) cp;
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        }
    }
}