package com.example.testing;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;

import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GameDetailActivity extends AppCompatActivity {

    private Web3Repository repository;
    private int targetGameId;

    // 预设几组不同选项的图表颜色
    private final int[] CHART_COLORS = {0xFF0052FF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_detail);

        String privateKey = getIntent().getStringExtra("PRIVATE_KEY");
        targetGameId = getIntent().getIntExtra("GAME_ID", -1);

        if (privateKey == null || targetGameId == -1) {
            finish();
            return;
        }

        repository = new Web3Repository(privateKey);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        loadMarketData();
    }

    private void loadMarketData() {
        repository.getGames(new Web3Repository.DataCallback<List<Web3Repository.GameModel>>() {
            @Override
            public void onSuccess(List<Web3Repository.GameModel> games) {
                for (Web3Repository.GameModel game : games) {
                    if (game.id == targetGameId) {
                        renderDetail(game);
                        return;
                    }
                }
                Toast.makeText(GameDetailActivity.this, "未找到该市场数据", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(String error) {
                Toast.makeText(GameDetailActivity.this, "加载失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void renderDetail(Web3Repository.GameModel game) {
        TextView tvTitle = findViewById(R.id.tv_detail_title);
        TextView tvVolume = findViewById(R.id.tv_detail_volume);
        TextView tvStatus = findViewById(R.id.tv_detail_status);
        TextView tvCondition = findViewById(R.id.tv_detail_condition);
        TextView tvDeadline = findViewById(R.id.tv_detail_deadline);
        LinearLayout llOptions = findViewById(R.id.ll_detail_options);

        tvTitle.setText(game.desc);
        tvVolume.setText("总交易量: " + formatWei(game.totalPool) + " BKC");
        tvCondition.setText("清算规则: " + game.condition);

        // 🌟 核心修复 4：直接传入链上返回的毫秒级时间，不再乘以 1000
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        tvDeadline.setText("截止时间: " + sdf.format(new Date(game.deadlineSec)));

        // 🌟 核心修复 5：使用毫秒进行时间判断
        long currentMs = System.currentTimeMillis();

        if (game.isResolved) {
            tvStatus.setText("已决议");
            tvStatus.setTextColor(0xFF10B981);
        } else if (currentMs > game.deadlineSec || game.isRefunded) {
            tvStatus.setText("已结束");
            tvStatus.setTextColor(0xFF64748B);
        } else {
            tvStatus.setText("🟢 交易进行中");
        }

        llOptions.removeAllViews();
        List<Float> finalProbabilities = new ArrayList<>();
        BigDecimal totalPoolBd = new BigDecimal(game.totalPool);

        for (int i = 0; i < game.optionCount; i++) {
            final int optionIndex = i;
            String optName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项 " + (i + 1);
            final String currentOptName = optName;
            BigInteger poolSize = game.optionPools.get(i);

            float prob = 0f;
            if (totalPoolBd.compareTo(BigDecimal.ZERO) > 0) {
                prob = new BigDecimal(poolSize).divide(totalPoolBd, 4, RoundingMode.HALF_UP).floatValue() * 100;
            }
            finalProbabilities.add(prob);

            LinearLayout optLayout = new LinearLayout(this);
            optLayout.setOrientation(LinearLayout.HORIZONTAL);
            optLayout.setGravity(Gravity.CENTER_VERTICAL);
            optLayout.setPadding(32, 32, 32, 32);
            optLayout.setBackgroundColor(0xFFFFFFFF);

            TextView tvOptName = new TextView(this);
            tvOptName.setText(optName);
            tvOptName.setTextColor(0xFF0F172A);
            tvOptName.setTextSize(16);
            tvOptName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvOptName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView tvProb = new TextView(this);
            tvProb.setText(String.format("%.1f%%", prob));
            tvProb.setTextColor(CHART_COLORS[i % CHART_COLORS.length]);
            tvProb.setTextSize(16);
            tvProb.setTypeface(null, android.graphics.Typeface.BOLD);
            tvProb.setPadding(0, 0, 32, 0);

            MaterialButton btnBuy = new MaterialButton(this);
            btnBuy.setText("买入");
            btnBuy.setBackgroundColor(0xFFEFF6FF);
            btnBuy.setTextColor(0xFF0052FF);
            btnBuy.setCornerRadius(12);

            // 🌟 核心修复 6：按钮状态也使用毫秒判断
            if (game.isResolved || game.isRefunded || currentMs > game.deadlineSec) {
                btnBuy.setEnabled(false);
                btnBuy.setBackgroundColor(0xFFF1F5F9);
                btnBuy.setTextColor(0xFF94A3B8);
            } else {
                btnBuy.setOnClickListener(v -> showStakeDialog(game.id, optionIndex, currentOptName));
            }

            optLayout.addView(tvOptName);
            optLayout.addView(tvProb);
            optLayout.addView(btnBuy);
            llOptions.addView(optLayout);

            if (i < game.optionCount - 1) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));
                divider.setBackgroundColor(0xFFF1F5F9);
                llOptions.addView(divider);
            }
        }

        setupChart(game, finalProbabilities);
    }

    private void setupChart(Web3Repository.GameModel game, List<Float> finalProbabilities) {
        LineChart chart = findViewById(R.id.chart_market_trend);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(true);
        chart.setDrawGridBackground(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(false);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(0xFF94A3B8);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);
        leftAxis.setTextColor(0xFF94A3B8);
        leftAxis.setGridColor(0xFFF1F5F9);
        chart.getAxisRight().setEnabled(false);

        LineData lineData = new LineData();

        for (int i = 0; i < finalProbabilities.size(); i++) {
            List<Entry> entries = new ArrayList<>();
            float finalProb = finalProbabilities.get(i);
            float currentSimulatedProb = 100f / finalProbabilities.size();

            for (int day = 0; day < 10; day++) {
                entries.add(new Entry(day, currentSimulatedProb));
                currentSimulatedProb += (finalProb - currentSimulatedProb) / (10 - day) + (float) (Math.random() * 5 - 2.5);
                if (currentSimulatedProb < 0) currentSimulatedProb = 0;
                if (currentSimulatedProb > 100) currentSimulatedProb = 100;
            }
            entries.add(new Entry(10, finalProb));

            String label = (i < game.optionNames.size()) ? game.optionNames.get(i) : "Option " + (i + 1);
            LineDataSet dataSet = new LineDataSet(entries, label);
            int color = CHART_COLORS[i % CHART_COLORS.length];
            dataSet.setColor(color);
            dataSet.setCircleColor(color);
            dataSet.setLineWidth(2.5f);
            dataSet.setCircleRadius(4f);
            dataSet.setDrawValues(false);

            lineData.addDataSet(dataSet);
        }

        chart.setData(lineData);
        chart.animateX(1000);
    }

    private String formatWei(BigInteger wei) {
        return org.web3j.utils.Convert.fromWei(new BigDecimal(wei), org.web3j.utils.Convert.Unit.ETHER).setScale(1, RoundingMode.HALF_UP).toString();
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
                Toast.makeText(GameDetailActivity.this, message + " (正在等待区块确认...)", Toast.LENGTH_LONG).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> loadMarketData(), 1000);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(GameDetailActivity.this, error != null ? error : "交易失败", Toast.LENGTH_LONG).show();
            }
        });
    }
}