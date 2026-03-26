package com.example.testing;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.testing.databinding.ActivityMainBinding;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;

import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private Web3Repository repository;
    private List<Web3Repository.GameModel> allGamesList = new ArrayList<>();
    private String privateKey;

    private static final String CONTRACT_BYTECODE = "0x"; // <--- 填入你的 bytecode

    private enum ViewMode {HOME, PORTFOLIO, CREATE, PROFILE}

    private ViewMode currentMode = ViewMode.HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        privateKey = getIntent().getStringExtra("PRIVATE_KEY");
        if (privateKey == null) {
            finish();
            return;
        }

        repository = new Web3Repository(privateKey);
        setupNavigation();
        setupProfile();
        setupCreateTabLogic();
        setupUI();

        View cvResolve = findViewById(R.id.cv_resolve_market);
        if (cvResolve != null) cvResolve.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncData();
    }

    private String getFriendlyErrorMessage(String rawError) {
        if (rawError == null) return "发生未知错误，请重试";
        String lower = rawError.toLowerCase();
        if (lower.contains("reverted")) return "交易被拒绝 (条件不满足，或池子已结算)";
        if (lower.contains("insufficient funds")) return "您的 BKC 余额不足";
        if (lower.contains("timeout") || lower.contains("network")) return "网络连接超时";
        if (lower.contains("past deadline")) return "已超过预测截止时间";
        return "链上交互失败: " + rawError;
    }

    private void setupNavigation() {
        binding.navHome.setOnClickListener(v -> switchTab(ViewMode.HOME));
        binding.navPortfolio.setOnClickListener(v -> switchTab(ViewMode.PORTFOLIO));
        binding.navCreate.setOnClickListener(v -> switchTab(ViewMode.CREATE));
        binding.navProfile.setOnClickListener(v -> switchTab(ViewMode.PROFILE));
    }

    private void switchTab(ViewMode mode) {
        currentMode = mode;
        binding.viewHome.setVisibility((mode == ViewMode.HOME || mode == ViewMode.PORTFOLIO) ? View.VISIBLE : View.GONE);
        binding.viewCreate.setVisibility(mode == ViewMode.CREATE ? View.VISIBLE : View.GONE);
        binding.viewProfile.setVisibility(mode == ViewMode.PROFILE ? View.VISIBLE : View.GONE);

        if (mode == ViewMode.HOME) binding.tvTopTitle.setText("发现市场");
        else if (mode == ViewMode.PORTFOLIO) binding.tvTopTitle.setText("我的持仓");

        int activeColor = 0xFF0052FF;
        int inactiveColor = 0xFF64748B;

        binding.navHome.setTextColor(mode == ViewMode.HOME ? activeColor : inactiveColor);
        binding.navPortfolio.setTextColor(mode == ViewMode.PORTFOLIO ? activeColor : inactiveColor);
        binding.navCreate.setTextColor(mode == ViewMode.CREATE ? activeColor : inactiveColor);
        binding.navProfile.setTextColor(mode == ViewMode.PROFILE ? activeColor : inactiveColor);

        if (mode == ViewMode.HOME || mode == ViewMode.PORTFOLIO) {
            filterGames(binding.etSearchBar.getText().toString());
        }
    }

    private void setupProfile() {
        String address = repository.getWalletAddress();
        TextView tvAddress = findViewById(R.id.tv_profile_address);
        if (tvAddress != null) tvAddress.setText(address);
        String letter = address.length() > 2 ? address.substring(2, 3).toUpperCase() : "?";
        TextView tvAvatar = findViewById(R.id.tv_avatar_letter);
        if (tvAvatar != null) tvAvatar.setText(letter);
        findViewById(R.id.btn_logout).setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
        LineChart pnlChart = findViewById(R.id.chart_profile_pnl);
        if (pnlChart != null) {
            pnlChart.getDescription().setEnabled(false);
            pnlChart.getLegend().setEnabled(false);
            pnlChart.getAxisRight().setEnabled(false);
            pnlChart.getXAxis().setDrawGridLines(false);
            pnlChart.getXAxis().setDrawLabels(false);
            pnlChart.getAxisLeft().setDrawGridLines(false);
            pnlChart.getAxisLeft().setDrawLabels(false);
            pnlChart.setTouchEnabled(false);
            List<Entry> entries = new ArrayList<>();
            float balance = 100f;
            for (int i = 0; i < 20; i++) {
                entries.add(new Entry(i, balance));
                balance += (float) (Math.random() * 4 - 1);
            }
            LineDataSet dataSet = new LineDataSet(entries, "Asset");
            dataSet.setColor(0xFF10B981);
            dataSet.setLineWidth(3f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            dataSet.setDrawFilled(true);
            dataSet.setFillColor(0xFF10B981);
            dataSet.setFillAlpha(30);
            pnlChart.setData(new LineData(dataSet));
            pnlChart.invalidate();
        }
    }

    private void setupCreateTabLogic() {
        MaterialButton btnDeploy = findViewById(R.id.btn_deploy_game);
        if (btnDeploy != null) {
            btnDeploy.setOnClickListener(v -> {
                String desc = ((EditText) findViewById(R.id.et_desc)).getText().toString().trim();
                String cond = ((EditText) findViewById(R.id.et_condition)).getText().toString().trim();
                String optsStr = ((EditText) findViewById(R.id.et_options)).getText().toString().trim();
                String durationStr = ((EditText) findViewById(R.id.et_duration)).getText().toString().trim();

                if (TextUtils.isEmpty(desc) || TextUtils.isEmpty(cond) || TextUtils.isEmpty(optsStr) || TextUtils.isEmpty(durationStr)) {
                    Toast.makeText(this, "请完整填写所有发行信息", Toast.LENGTH_SHORT).show();
                    return;
                }

                String[] optsArray = optsStr.split(",");
                if (optsArray.length < 2) {
                    Toast.makeText(this, "至少需要2个选项", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 🌟 核心修复 1：将持续时间转换为毫秒 (乘以 60 * 1000)
                long durationMs = Long.parseLong(durationStr) * 60 * 1000;

                List<Utf8String> utf8List = new ArrayList<>();
                for (String s : optsArray) utf8List.add(new Utf8String(s.trim()));
                DynamicArray<Utf8String> web3jOptions = new DynamicArray<>(Utf8String.class, utf8List);

                Function f = new Function("createGame", Arrays.asList(
                        new Utf8String(desc), new Utf8String(cond), web3jOptions, new Uint256(durationMs) // 传入毫秒
                ), Collections.emptyList());

                executeTx(BigInteger.ZERO, f, "🎉 预测池发行成功！");
            });
        }

        MaterialButton btnResolve = findViewById(R.id.btn_resolve_game);
        if (btnResolve != null) {
            btnResolve.setOnClickListener(v -> {
                String gameIdStr = ((EditText) findViewById(R.id.et_game_id)).getText().toString().trim();
                String winOptStr = ((EditText) findViewById(R.id.et_winning_opt)).getText().toString().trim();

                if (TextUtils.isEmpty(gameIdStr) || TextUtils.isEmpty(winOptStr)) {
                    Toast.makeText(this, "请输入要清算的博弈池ID及获胜选项", Toast.LENGTH_SHORT).show();
                    return;
                }

                int gameId = Integer.parseInt(gameIdStr);
                int winOpt = Integer.parseInt(winOptStr);

                Toast.makeText(this, "⚙️ 正在计算证明...", Toast.LENGTH_LONG).show();
                btnResolve.setEnabled(false);
                btnResolve.setText("处理中...");

                AppExecutors.getInstance().computeIO().execute(() -> {
                    try {
                        String seed = "BrokerChain_" + gameId + "_" + System.currentTimeMillis();
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] hash = seed.getBytes();
                        for (int i = 0; i < 500_000; i++) hash = digest.digest(hash);
                        byte[] finalHash = hash;

                        AppExecutors.getInstance().mainThread().execute(() -> {
                            btnResolve.setEnabled(true);
                            btnResolve.setText("执行预言机决议广播");
                            Function f = new Function("resolveGame", Arrays.asList(
                                    new Uint256(gameId), new Uint8(winOpt), new Bytes32(finalHash)
                            ), Collections.emptyList());
                            executeTx(BigInteger.ZERO, f, "✅ 预言机开奖决议已上链！");
                        });
                    } catch (Exception e) {
                        AppExecutors.getInstance().mainThread().execute(() -> {
                            btnResolve.setEnabled(true);
                            btnResolve.setText("执行预言机决议广播");
                            Toast.makeText(this, "计算失败", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            });
        }
    }

    private void setupUI() {
        binding.etSearchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterGames(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void syncData() {
        repository.getBalance(new Web3Repository.DataCallback<BigDecimal>() {
            @Override
            public void onSuccess(BigDecimal bkc) {
                String formattedBalance = bkc.setScale(2, RoundingMode.HALF_UP).toString();
                binding.tvWalletBalanceTop.setText(formattedBalance + " BKC");
                TextView tvProfileBalance = findViewById(R.id.tv_profile_total_balance);
                if (tvProfileBalance != null) {
                    tvProfileBalance.setText(formattedBalance);
                }
            }
            @Override
            public void onError(String error) { }
        });

        binding.llLobbyContainer.removeAllViews();
        TextView loadingView = new TextView(this);
        loadingView.setText("正在同步链上合约数据...");
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setPadding(0, 50, 0, 50);
        loadingView.setTextColor(0xFF64748B);
        binding.llLobbyContainer.addView(loadingView);

        repository.getGames(new Web3Repository.DataCallback<List<Web3Repository.GameModel>>() {
            @Override
            public void onSuccess(List<Web3Repository.GameModel> games) {
                allGamesList = games;
                if (currentMode == ViewMode.HOME || currentMode == ViewMode.PORTFOLIO) {
                    filterGames(binding.etSearchBar.getText().toString());
                }
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, getFriendlyErrorMessage(error), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterGames(String query) {
        List<Web3Repository.GameModel> filtered = new ArrayList<>();
        for (Web3Repository.GameModel game : allGamesList) {
            if (currentMode == ViewMode.PORTFOLIO) {
                boolean invested = false;
                for (BigInteger s : game.myStakes) {
                    if (s.signum() > 0) invested = true;
                }
                if (!invested) continue;
            }
            if (query.isEmpty() || game.desc.toLowerCase().contains(query.toLowerCase())) {
                filtered.add(game);
            }
        }
        renderDynamicLobby(filtered);
    }

    private void renderDynamicLobby(List<Web3Repository.GameModel> games) {
        binding.llLobbyContainer.removeAllViews();

        if (games.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(currentMode == ViewMode.PORTFOLIO ? "您目前没有参与任何交易" : "暂无符合条件的市场");
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(0, 100, 0, 100);
            emptyView.setTextColor(0xFF94A3B8);
            binding.llLobbyContainer.addView(emptyView);
            return;
        }

        // 🌟 核心修复 2：获取当前的毫秒级时间戳
        long currentMs = System.currentTimeMillis();

        for (Web3Repository.GameModel game : games) {
            View itemView = getLayoutInflater().inflate(R.layout.item_game, binding.llLobbyContainer, false);

            itemView.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, GameDetailActivity.class);
                intent.putExtra("PRIVATE_KEY", privateKey);
                intent.putExtra("GAME_ID", game.id);
                startActivity(intent);
            });

            TextView tvTitle = itemView.findViewById(R.id.tv_item_game_title);
            TextView tvVolume = itemView.findViewById(R.id.tv_item_volume);
            TextView tvStatus = itemView.findViewById(R.id.tv_item_status);
            LinearLayout llOptions = itemView.findViewById(R.id.ll_options_container);

            tvTitle.setText("ID " + game.id + " | " + game.desc);
            tvVolume.setText("总交易量: " + formatWei(game.totalPool) + " BKC");

            // 🌟 核心修复 3：使用毫秒进行对比
            boolean isExpired = currentMs > game.deadlineSec;

            if (game.isResolved) {
                String winnerName = game.winningOption < game.optionNames.size() ? game.optionNames.get(game.winningOption) : "未知";
                tvStatus.setText("✅ 已决议: " + winnerName);
                tvStatus.setTextColor(0xFF10B981);
            } else if (game.isRefunded) {
                tvStatus.setText("↩️ 超时已流局");
                tvStatus.setTextColor(0xFFEF4444);
            } else if (isExpired) {
                tvStatus.setText("⏳ 等待预言机输入");
                tvStatus.setTextColor(0xFFF59E0B);
            } else {
                tvStatus.setText("🟢 交易进行中");
                tvStatus.setTextColor(0xFF0052FF);
            }

            for (int i = 0; i < game.optionCount; i++) {
                int optionId = i;
                String realOptName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项 " + optionId;
                BigInteger poolSize = game.optionPools.get(i);
                BigInteger myStake = game.myStakes.get(i);

                LinearLayout optLayout = new LinearLayout(this);
                optLayout.setOrientation(LinearLayout.HORIZONTAL);
                optLayout.setGravity(Gravity.CENTER_VERTICAL);
                optLayout.setPadding(32, 24, 32, 24);

                if (i % 2 == 1) optLayout.setBackgroundColor(0xFFF8FAFC);

                TextView tvOptName = new TextView(this);
                tvOptName.setText(realOptName + (myStake.signum() > 0 ? " (已投: " + formatWei(myStake) + ")" : ""));
                tvOptName.setTextColor(0xFF1E293B);
                tvOptName.setTextSize(14);
                tvOptName.setTypeface(null, android.graphics.Typeface.BOLD);
                tvOptName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                MaterialButton btnBuy = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                btnBuy.setText("质押 · 池 " + formatWei(poolSize));
                btnBuy.setTextColor(0xFF0052FF);
                btnBuy.setStrokeColorResource(android.R.color.transparent);
                btnBuy.setBackgroundColor(0xFFEFF6FF);
                btnBuy.setCornerRadius(12);
                btnBuy.setMinimumHeight(0);
                btnBuy.setMinHeight(0);
                btnBuy.setPadding(30, 10, 30, 10);

                if (game.isResolved || game.isRefunded || isExpired) {
                    btnBuy.setEnabled(false);
                    btnBuy.setBackgroundColor(0xFFF1F5F9);
                    btnBuy.setTextColor(0xFF94A3B8);
                    btnBuy.setText("停止交易");
                } else {
                    btnBuy.setOnClickListener(v -> showStakeDialog(game.id, optionId, realOptName));
                }

                optLayout.addView(tvOptName);
                optLayout.addView(btnBuy);
                llOptions.addView(optLayout);
            }
            binding.llLobbyContainer.addView(itemView);
        }
    }

    private void showStakeDialog(int gameId, int optionId, String optName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认交易策略");
        builder.setMessage("您正在质押：\n[" + optName + "]\n\n请输入投入的 BKC 数量:");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setPadding(50, 40, 50, 40);
        builder.setView(input);

        builder.setPositiveButton("签名并广播", (dialog, which) -> {
            String amountStr = input.getText().toString();
            if (!amountStr.isEmpty()) {
                try {
                    BigDecimal amount = new BigDecimal(amountStr);
                    BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();
                    Function f = new Function("stakeTokens", Arrays.asList(new Uint256(gameId), new Uint8(optionId)), Collections.emptyList());
                    executeTx(wei, f, "质押成功，已上链确认！");
                } catch (Exception e) {
                    Toast.makeText(this, "输入的金额格式不正确", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void executeTx(BigInteger value, Function f, String successMsg) {
        Toast.makeText(this, "⏳ 正在广播交易...", Toast.LENGTH_SHORT).show();
        repository.sendTransaction(value, f, successMsg, new Web3Repository.TxCallback() {
            @Override
            public void onTxSent(String txHash) {}

            @Override
            public void onConfirmed(String message) {
                Toast.makeText(MainActivity.this, message + " (正在等待区块确认...)", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    syncData();
                    if (currentMode == ViewMode.CREATE) switchTab(ViewMode.HOME);
                }, 1000);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, getFriendlyErrorMessage(error), Toast.LENGTH_LONG).show();
            }
        });
    }

    private String formatWei(BigInteger wei) {
        return org.web3j.utils.Convert.fromWei(new BigDecimal(wei), org.web3j.utils.Convert.Unit.ETHER).setScale(1, RoundingMode.HALF_UP).toString();
    }
}