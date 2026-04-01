package com.example.testing;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class GameDetailActivity extends AppCompatActivity {

    private Web3Repository repository;
    private int targetGameId;
    private Web3Repository.GameModel currentGameData;

    private final int[] CHART_COLORS = {0xFF0052FF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981};

    // ==========================================
    // 🌟 创新点：本地自动化交易机器人 (Limit Order Bot)
    // ==========================================
    private static class LimitOrder {
        int optionId;         // 目标选项 ID
        String optName;       // 选项名称
        float targetPrice;    // 设定的买入限价
        BigInteger amountWei; // 投入的资金量
        boolean isProcessing; // 防重复提交锁
    }

    private final List<LimitOrder> activeLimitOrders = new ArrayList<>();
    private final Handler autoTradeHandler = new Handler(Looper.getMainLooper());
    private Runnable autoTradeRunnable;

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

        // 🌟 开启后台自动交易守护进程：每 5 秒检查一次链上价格
        autoTradeRunnable = new Runnable() {
            @Override
            public void run() {
                if (!activeLimitOrders.isEmpty()) {
                    loadMarketData(); // 只要有挂单，就高频拉取最新盘口数据
                }
                autoTradeHandler.postDelayed(this, 5000);
            }
        };
        autoTradeHandler.postDelayed(autoTradeRunnable, 5000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出页面时关闭自动交易引擎，释放内存
        autoTradeHandler.removeCallbacks(autoTradeRunnable);
    }

    private void loadMarketData() {
        repository.getGameDetail(targetGameId, new Web3Repository.DataCallback<Web3Repository.GameModel>() {
            @Override
            public void onSuccess(Web3Repository.GameModel game) {
                currentGameData = game;
                renderDetail(game);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(GameDetailActivity.this, "数据刷新失败: " + error, Toast.LENGTH_SHORT).show();
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
        tvVolume.setText("真实交易量: " + formatWei(game.totalPool) + " BKC");
        tvCondition.setText("清算规则: " + game.condition);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        tvDeadline.setText("截止时间: " + sdf.format(new Date(game.deadlineSec)));

        long currentMs = System.currentTimeMillis();
        boolean isMarketClosed = game.isResolved || game.isRefunded || currentMs > game.deadlineSec;

        if (game.isResolved) {
            tvStatus.setText("已决议");
            tvStatus.setTextColor(0xFF10B981);
        } else if (isMarketClosed) {
            tvStatus.setText("已结束");
            tvStatus.setTextColor(0xFF64748B);
        } else {
            tvStatus.setText("🟢 交易进行中");
        }

        llOptions.removeAllViews();
        List<Float> finalProbabilities = new ArrayList<>();

        BigDecimal totalVirtualBd = BigDecimal.ZERO;
        for (int i = 0; i < game.optionCount; i++) {
            totalVirtualBd = totalVirtualBd.add(new BigDecimal(game.virtualReserves.get(i)));
        }

        // ==========================================
        // 🌟 核心：计算价格 & 监控是否触发限价单
        // ==========================================
        List<LimitOrder> triggeredOrders = new ArrayList<>();

        for (int i = 0; i < game.optionCount; i++) {
            final int optionIndex = i;
            String optName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项 " + (i + 1);

            BigInteger virtualReserve = game.virtualReserves.get(i);

            float prob = 0f;
            if (totalVirtualBd.compareTo(BigDecimal.ZERO) > 0) {
                prob = new BigDecimal(virtualReserve).divide(totalVirtualBd, 4, RoundingMode.HALF_UP).floatValue() * 100;
            }
            finalProbabilities.add(prob);

            float priceYes = prob / 100f;
            float priceNo = 1f - priceYes;

            // 🤖 自动交易引擎：检查价格是否达标
            Iterator<LimitOrder> iterator = activeLimitOrders.iterator();
            while (iterator.hasNext()) {
                LimitOrder order = iterator.next();
                if (order.optionId == optionIndex && !order.isProcessing) {
                    // 当实时价格 <= 设定的目标限价时，触发买入！
                    if (priceYes <= order.targetPrice) {
                        order.isProcessing = true;
                        triggeredOrders.add(order);
                        iterator.remove(); // 从等待队列移除
                    }
                }
            }

            // 渲染常规 UI (复用我们创建的 item_option_row.xml)
            View rowView = getLayoutInflater().inflate(R.layout.item_option_row, llOptions, false);

            TextView tvOptName = rowView.findViewById(R.id.tv_row_opt_name);
            TextView tvOptShares = rowView.findViewById(R.id.tv_row_opt_shares);
            TextView tvProb = rowView.findViewById(R.id.tv_row_prob);
            MaterialButton btnBuyYes = rowView.findViewById(R.id.btn_row_buy_yes);
            MaterialButton btnBuyNo = rowView.findViewById(R.id.btn_row_buy_no);

            tvOptName.setText(optName);
            tvProb.setText(String.format("%.0f%%", prob));

            if (game.myShares != null && game.myShares.size() > i && game.myShares.get(i).signum() > 0) {
                tvOptShares.setVisibility(View.VISIBLE);
                tvOptShares.setText("已持有 " + formatWei(game.myShares.get(i)) + " 份");
            }

            btnBuyYes.setText(String.format("Yes %.2f", priceYes));
            btnBuyNo.setText(String.format("No %.2f", priceNo));

            if (isMarketClosed) {
                btnBuyYes.setEnabled(false);
                btnBuyNo.setEnabled(false);
                btnBuyYes.setBackgroundColor(0xFFF1F5F9);
                btnBuyNo.setBackgroundColor(0xFFF1F5F9);
            } else {
                btnBuyYes.setOnClickListener(v -> showOrderDialog(game.id, optionIndex, optName, true, priceYes));
                btnBuyNo.setOnClickListener(v -> {
                    if (game.optionCount == 2) {
                        int oppositeIndex = (optionIndex == 0) ? 1 : 0;
                        String oppositeName = game.optionNames.get(oppositeIndex);
                        showOrderDialog(game.id, oppositeIndex, oppositeName, false, priceNo);
                    } else {
                        Toast.makeText(this, "多选市场暂不开放组合做空，请买入 Yes 份额。", Toast.LENGTH_LONG).show();
                    }
                });
            }

            View divider = rowView.findViewById(R.id.view_row_divider);
            if (divider != null && i == game.optionCount - 1) {
                divider.setVisibility(View.GONE);
            }
            llOptions.addView(rowView);
        }

        // 🌟 触发积压的限价单上链
        for (LimitOrder order : triggeredOrders) {
            Toast.makeText(this, "🤖 触发自动买入！[" + order.optName + "] 价格已跌至 " + order.targetPrice, Toast.LENGTH_LONG).show();
            Function f = new Function("buyShares", Arrays.asList(new Uint256(game.id), new Uint8(order.optionId)), Collections.emptyList());
            executeTx(order.amountWei, f, "限价委托单自动成交！");
        }

        // 🌟 渲染当前的“委托挂单簿”
        renderPendingOrdersUI(llOptions);

        setupChart(game, finalProbabilities);
    }

    /**
     * 渲染当前活跃的限价单列表
     */
    private void renderPendingOrdersUI(LinearLayout llOptions) {
        if (activeLimitOrders.isEmpty()) return;

        // 绘制分割标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("📋 运行中的限价委托单 (本地监控中)");
        tvTitle.setTextColor(0xFF0052FF);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        tvTitle.setPadding(padding, padding * 2, padding, padding);
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        llOptions.addView(tvTitle);

        // 绘制每一条挂单
        for (LimitOrder order : activeLimitOrders) {
            LinearLayout orderLayout = new LinearLayout(this);
            orderLayout.setOrientation(LinearLayout.HORIZONTAL);
            orderLayout.setGravity(Gravity.CENTER_VERTICAL);
            orderLayout.setPadding(padding, padding / 2, padding, padding / 2);

            TextView tvOrderInfo = new TextView(this);
            tvOrderInfo.setText(String.format("买入 [%s]\n触发限价: %.2f BKC | 投入金额: %s BKC",
                    order.optName, order.targetPrice, formatWei(order.amountWei)));
            tvOrderInfo.setTextColor(0xFF475569);
            tvOrderInfo.setTextSize(13f);
            tvOrderInfo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            MaterialButton btnCancel = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnCancel.setText("撤单");
            btnCancel.setTextColor(0xFFEF4444);
            btnCancel.setStrokeColorResource(android.R.color.transparent);
            btnCancel.setOnClickListener(v -> {
                activeLimitOrders.remove(order);
                Toast.makeText(this, "委托单已撤销", Toast.LENGTH_SHORT).show();
                if (currentGameData != null) renderDetail(currentGameData);
            });

            orderLayout.addView(tvOrderInfo);
            orderLayout.addView(btnCancel);
            llOptions.addView(orderLayout);
        }
    }

    /**
     * 弹出二合一交易框（支持市价单 / 限价单）
     */
    private void showOrderDialog(int gameId, int optionId, String optName, boolean isYes, float currentPrice) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isYes ? "买入看涨份额 (Buy Yes)" : "买入反向做空份额 (Buy No)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView tvInfo = new TextView(this);
        tvInfo.setText(String.format("标的: %s\n当前实时单价: %.2f BKC", optName, currentPrice));
        tvInfo.setTextColor(0xFF0F172A);
        tvInfo.setTextSize(14f);
        tvInfo.setPadding(0, 0, 0, padding / 2);
        layout.addView(tvInfo);

        final EditText etAmount = new EditText(this);
        etAmount.setHint("输入买入总金额 (BKC)");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setBackgroundResource(android.R.drawable.edit_text);
        layout.addView(etAmount);

        // 限价输入框
        final EditText etLimitPrice = new EditText(this);
        etLimitPrice.setHint(String.format("限价买入 (选填, 低于 %.2f 时自动执行)", currentPrice));
        etLimitPrice.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etLimitPrice.setBackgroundResource(android.R.drawable.edit_text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, padding / 2, 0, 0);
        etLimitPrice.setLayoutParams(lp);
        layout.addView(etLimitPrice);

        builder.setView(layout);

        builder.setPositiveButton("确认交易", (dialog, which) -> {
            String amtStr = etAmount.getText().toString().trim();
            String limitStr = etLimitPrice.getText().toString().trim();

            if (!amtStr.isEmpty()) {
                try {
                    BigDecimal amount = new BigDecimal(amtStr);
                    BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();

                    if (!limitStr.isEmpty()) {
                        float limitPrice = Float.parseFloat(limitStr);
                        // 🌟 核心逻辑：如果设置的价格低于当前价，进入挂单簿监控
                        if (limitPrice < currentPrice) {
                            LimitOrder order = new LimitOrder();
                            order.optionId = optionId;
                            order.optName = optName;
                            order.targetPrice = limitPrice;
                            order.amountWei = wei;
                            order.isProcessing = false;

                            activeLimitOrders.add(order);
                            Toast.makeText(this, "✅ 挂单成功！系统将在后台帮您盯盘", Toast.LENGTH_SHORT).show();
                            if (currentGameData != null) renderDetail(currentGameData); // 刷新显示委托单
                            return;
                        }
                    }

                    // 如果没填限价，或者限价 >= 当前价，直接市价买入！
                    Function f = new Function("buyShares", Arrays.asList(new Uint256(gameId), new Uint8(optionId)), Collections.emptyList());
                    executeTx(wei, f, "市价购买成功，份额已铸造！");
                } catch (Exception e) {
                    Toast.makeText(this, "输入的金额格式不正确", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "买入金额不能为空", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
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
        return org.web3j.utils.Convert.fromWei(new BigDecimal(wei), org.web3j.utils.Convert.Unit.ETHER)
                .setScale(1, RoundingMode.HALF_UP).toString();
    }

    private void executeTx(BigInteger value, Function f, String successMsg) {
        Toast.makeText(this, "⏳ 正在连接底层公链...", Toast.LENGTH_SHORT).show();

        repository.sendTransaction(value, f, successMsg, new Web3Repository.TxCallback() {
            @Override
            public void onTxSent(String txHash) {}

            @Override
            public void onConfirmed(String message) {
                Toast.makeText(GameDetailActivity.this, message + " (区块已确认)", Toast.LENGTH_LONG).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> loadMarketData(), 1000);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(GameDetailActivity.this, error != null ? error : "交易被网络拒绝", Toast.LENGTH_LONG).show();
            }
        });
    }
}