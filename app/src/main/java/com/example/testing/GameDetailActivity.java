package com.example.testing;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameDetailActivity extends AppCompatActivity {

    private Web3Repository repository;
    private int targetGameId;
    private Web3Repository.GameModel currentGameData;
    private String privateKey;

    // ==========================================
    // 🌟 AI 智能托管中心状态变量
    // ==========================================
    private boolean isAiManaged = false;
    private BigDecimal managedBetAmount = BigDecimal.ZERO;
    private long lastAiCheckTime = 0;
    private static final String DEEPSEEK_API_KEY = "sk-679c615e26234c67b00677ba689a80d8";

    private final int[] CHART_COLORS = {0xFF0052FF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981};

    private static class LimitOrder {
        int optionId;
        String optName;
        float targetPrice;
        BigInteger amountWei;
        boolean isProcessing;
    }

    private final List<LimitOrder> activeLimitOrders = new ArrayList<>();
    private final Handler autoTradeHandler = new Handler(Looper.getMainLooper());
    private Runnable autoTradeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_detail);

        privateKey = getIntent().getStringExtra("PRIVATE_KEY");

        targetGameId = -1;
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.containsKey("GAME_ID")) {
            Object idObj = extras.get("GAME_ID");
            if (idObj instanceof Number) {
                targetGameId = ((Number) idObj).intValue();
            } else if (idObj != null) {
                try {
                    targetGameId = Integer.parseInt(idObj.toString());
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }
        }

        if (privateKey == null || targetGameId == -1) {
            Toast.makeText(this, "安全拦截：参数传递失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        repository = new Web3Repository(privateKey);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 🌟 静态绑定托管按钮，避免重复刷新导致崩溃
        MaterialButton btnAiManaged = findViewById(R.id.btn_ai_managed_trade);
        if (btnAiManaged != null) {
            btnAiManaged.setOnClickListener(v -> {
                if (isAiManaged) {
                    isAiManaged = false;
                    Toast.makeText(this, "AI 托管已关闭", Toast.LENGTH_SHORT).show();
                    updateAiManageUI();
                } else {
                    showAiManageConfigDialog();
                }
            });
        }

        loadMarketData();

        // 🌟 后台守护进程 (带生命周期保护)
        autoTradeRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;

                if (!activeLimitOrders.isEmpty() || isAiManaged) {
                    loadMarketData();
                }

                if (isAiManaged && System.currentTimeMillis() - lastAiCheckTime > 30000) {
                    performAiBackgroundAnalysis();
                }

                autoTradeHandler.postDelayed(this, 5000);
            }
        };
        autoTradeHandler.postDelayed(autoTradeRunnable, 5000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoTradeHandler.removeCallbacks(autoTradeRunnable);
    }

    private void loadMarketData() {
        repository.getGameDetail(targetGameId, new Web3Repository.DataCallback<Web3Repository.GameModel>() {
            @Override
            public void onSuccess(Web3Repository.GameModel game) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    currentGameData = game;
                    renderDetail(game);
                });
            }
            @Override
            public void onError(String error) {
                // 静默处理网络波动
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

        ImageView ivAvatar = findViewById(R.id.iv_detail_avatar);
        if (ivAvatar != null && game.avatarUrl != null && !game.avatarUrl.isEmpty()) {
            MainActivity.loadNetworkImage(game.avatarUrl, ivAvatar);
        }

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

        MaterialButton btnAiManaged = findViewById(R.id.btn_ai_managed_trade);
        if (btnAiManaged != null) {
            btnAiManaged.setVisibility(isMarketClosed ? View.GONE : View.VISIBLE);
        }

        llOptions.removeAllViews();

        List<Float> finalProbabilities = new ArrayList<>();

        // 1. 第一个循环：计算总虚拟库存
        BigDecimal totalVirtualBd = BigDecimal.ZERO;
        for (int i = 0; i < game.optionCount; i++) {
            totalVirtualBd = totalVirtualBd.add(new BigDecimal(game.virtualReserves.get(i)));
        }

        // ==========================================
        // 🌟 核心修复：声明一个 final 变量供 Lambda 使用
        // ==========================================
        final BigDecimal finalTotalVirtualBd = totalVirtualBd;

        List<LimitOrder> triggeredOrders = new ArrayList<>();

        // 2. 第二个循环：渲染列表
        for (int i = 0; i < game.optionCount; i++) {
            final int optionIndex = i;
            String optName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项 " + (i + 1);

            BigInteger virtualReserve = game.virtualReserves.get(i);
            float prob = 0f;
            if (finalTotalVirtualBd.compareTo(BigDecimal.ZERO) > 0) {
                prob = new BigDecimal(virtualReserve).divide(finalTotalVirtualBd, 4, RoundingMode.HALF_UP).floatValue() * 100;
            }
            finalProbabilities.add(prob);

            float priceYes = prob / 100f;
            float priceNo = 1f - priceYes;

            Iterator<LimitOrder> iterator = activeLimitOrders.iterator();
            while (iterator.hasNext()) {
                LimitOrder order = iterator.next();
                if (order.optionId == optionIndex && !order.isProcessing) {
                    if (priceYes <= order.targetPrice) {
                        order.isProcessing = true;
                        triggeredOrders.add(order);
                        iterator.remove();
                    }
                }
            }

            View rowView = getLayoutInflater().inflate(R.layout.item_option_row, llOptions, false);
            TextView tvOptName = rowView.findViewById(R.id.tv_row_opt_name);
            TextView tvOptShares = rowView.findViewById(R.id.tv_row_opt_shares);
            TextView tvProb = rowView.findViewById(R.id.tv_row_prob);
            MaterialButton btnBuyYes = rowView.findViewById(R.id.btn_row_buy_yes);
            MaterialButton btnBuyNo = rowView.findViewById(R.id.btn_row_buy_no);

            tvOptName.setText(optName);
            tvProb.setText(String.format("%.0f%%", prob));

            // ==========================================
            // 🌟 动态植入“卖出平仓”UI交互
            // ==========================================
            if (game.myShares != null && game.myShares.size() > i && game.myShares.get(i).signum() > 0) {
                tvOptShares.setVisibility(View.VISIBLE);
                String sharesStr = formatWei(game.myShares.get(i));
                tvOptShares.setText(String.format("持有 %s 份 (点击卖出)", sharesStr));

                // 将文字设置为可点击的品牌蓝色，暗示可交互
                tvOptShares.setTextColor(0xFF0052FF);
                tvOptShares.setBackgroundResource(android.R.drawable.list_selector_background);
                tvOptShares.setPadding(10, 5, 10, 5);

                if (!isMarketClosed) {
                    // 添加点击事件，唤出专属的 Sell 面板
                    tvOptShares.setOnClickListener(v -> showSellDialog(
                            game.id,
                            optionIndex,
                            optName,
                            game.myShares.get(optionIndex),
                            game.virtualReserves.get(optionIndex),
                            finalTotalVirtualBd // ✅ 这里传入定格后的 final 变量，完美解决报错！
                    ));
                }
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
                        Toast.makeText(this, "多选市场暂不开放组合做空，请直接买入 Yes 份额。", Toast.LENGTH_LONG).show();
                    }
                });
            }

            View divider = rowView.findViewById(R.id.view_row_divider);
            if (divider != null && i == game.optionCount - 1) {
                divider.setVisibility(View.GONE);
            }
            llOptions.addView(rowView);
        }

        for (LimitOrder order : triggeredOrders) {
            Toast.makeText(this, "🤖 触发自动买入！[" + order.optName + "] 价格已跌至 " + order.targetPrice, Toast.LENGTH_LONG).show();
            Function f = new Function("buyShares", Arrays.asList(new Uint256(game.id), new Uint8(order.optionId)), Collections.emptyList());
            executeTx(order.amountWei, f, "限价委托单自动成交！");
        }

        renderPendingOrdersUI(llOptions);
        setupChart(game, finalProbabilities);

        MaterialButton btnAi = findViewById(R.id.btn_ai_analysis);
        if (btnAi != null) {
            btnAi.setOnClickListener(null);
            btnAi.setOnClickListener(v -> {
                StringBuilder prompt = new StringBuilder();
                prompt.append("请帮我分析一下这个基于 AMM 的 Polymarket 模式预测市场，并给出投资策略：\n\n");
                prompt.append("【博弈主题】: ").append(game.desc).append("\n");
                prompt.append("【清算规则】: ").append(game.condition).append("\n");
                prompt.append("【详细背景】: ").append((game.detailedInfo == null || game.detailedInfo.isEmpty()) ? "无" : game.detailedInfo).append("\n");
                prompt.append("【当前真实总流动性】: ").append(formatWei(game.totalPool)).append(" BKC\n\n");
                prompt.append("目前盘口各选项的 AMM 实时定价如下：\n");
                for (int i = 0; i < game.optionCount; i++) {
                    String optName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项" + (i + 1);
                    float price = finalProbabilities.get(i) / 100f;
                    prompt.append("- [").append(optName).append("] : ")
                            .append("当前单价为 ").append(String.format("%.2f BKC", price))
                            .append(" （隐含胜率 ").append(String.format("%.1f%%", finalProbabilities.get(i))).append("）\n");
                }
                prompt.append("\n结合基本面，请问哪个选项被市场低估？请给出推演。");

                android.content.Intent intent = new android.content.Intent(GameDetailActivity.this, AiAnalysisActivity.class);
                intent.putExtra("INITIAL_PROMPT", prompt.toString());
                intent.putExtra("PRIVATE_KEY", privateKey);
                intent.putExtra("GAME_ID", targetGameId);
                startActivity(intent);
            });
        }
    }

    // ==========================================
    // 🌟 全新添加：卖出平仓操作面板
    // ==========================================
    private void showSellDialog(int gameId, int optionId, String optName, BigInteger myShares, BigInteger currentReserve, BigDecimal totalVirtual) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("卖出平仓 (Sell Shares)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView tvInfo = new TextView(this);
        String maxSharesStr = formatWei(myShares);
        tvInfo.setText(String.format("标的: %s\n可卖出最大份额: %s", optName, maxSharesStr));
        tvInfo.setTextColor(0xFF0F172A);
        tvInfo.setTextSize(14f);
        tvInfo.setPadding(0, 0, 0, padding / 2);
        layout.addView(tvInfo);

        final EditText etAmount = new EditText(this);
        etAmount.setHint("输入要卖出的份额数量");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setBackgroundResource(android.R.drawable.edit_text);
        layout.addView(etAmount);

        // 动态计算预计退回金额的提示文字
        TextView tvEstimate = new TextView(this);
        tvEstimate.setTextColor(0xFF10B981); // 绿色
        tvEstimate.setTextSize(12f);
        tvEstimate.setPadding(0, padding / 4, 0, 0);
        tvEstimate.setText("预计退回: 0.00 BKC (含 2% 保护费率)");
        layout.addView(tvEstimate);

        // 监听用户输入，实时计算退回金额
        etAmount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    String input = s.toString();
                    if (input.isEmpty()) {
                        tvEstimate.setText("预计退回: 0.00 BKC (含 2% 保护费率)");
                        return;
                    }
                    BigDecimal sellAmount = new BigDecimal(input);
                    BigDecimal reserveBd = new BigDecimal(currentReserve);
                    // 理论退回金额 = (卖出份额 * 该选项虚拟库存) / 总虚拟库存
                    BigDecimal rawReturn = sellAmount.multiply(reserveBd).divide(totalVirtual, 4, RoundingMode.HALF_UP);
                    // 扣除 2%
                    BigDecimal finalReturn = rawReturn.multiply(new BigDecimal("0.98"));
                    tvEstimate.setText(String.format("预计退回: %.4f BKC (含 2%% 保护费率)", finalReturn.floatValue()));
                } catch (Exception e) {
                    tvEstimate.setText("输入格式有误");
                }
            }
        });

        builder.setView(layout);

        builder.setPositiveButton("确认卖出", (dialog, which) -> {
            String amtStr = etAmount.getText().toString().trim();
            if (!amtStr.isEmpty()) {
                try {
                    BigDecimal amount = new BigDecimal(amtStr);
                    BigInteger sellWei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();

                    if (sellWei.compareTo(myShares) > 0) {
                        Toast.makeText(this, "卖出数额不能超过您的最大持有量！", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 调用智能合约的 sellShares 函数
                    Function f = new Function("sellShares", Arrays.asList(
                            new Uint256(gameId),
                            new Uint8(optionId),
                            new Uint256(sellWei)
                    ), Collections.emptyList());

                    // 注意：卖出操作不支付 BKC，所以 value 传 ZERO
                    executeTx(BigInteger.ZERO, f, "卖出平仓成功，BKC已退回钱包！");

                } catch (Exception e) {
                    Toast.makeText(this, "输入的数额格式不正确", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "卖出数额不能为空", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void updateAiManageUI() {
        MaterialButton btnAiManaged = findViewById(R.id.btn_ai_managed_trade);
        if (btnAiManaged == null) return;

        if (isAiManaged) {
            btnAiManaged.setText("🛑 停止 AI 智能托管 (运行中)");
            btnAiManaged.setBackgroundColor(0xFFEF4444);
        } else {
            btnAiManaged.setText("🤖 开启 AI 智能托管");
            btnAiManaged.setBackgroundColor(0xFF0052FF);
        }
    }

    private void showAiManageConfigDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("配置 AI 自动交易参数");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        TextView label1 = new TextView(this);
        label1.setText("允许 AI 每次调用的资金 (BKC):");
        layout.addView(label1);

        final EditText etAmt = new EditText(this);
        etAmt.setHint("如: 5");
        etAmt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(etAmt);

        TextView label2 = new TextView(this);
        label2.setText("\n策略风格偏好:");
        label2.setPadding(0, 16, 0, 8);
        layout.addView(label2);

        RadioGroup rg = new RadioGroup(this);
        RadioButton rb1 = new RadioButton(this);
        rb1.setText("稳健 (偏差 >20% 时才出手)");
        rb1.setChecked(true);
        RadioButton rb2 = new RadioButton(this);
        rb2.setText("激进 (任何套利空间即出手)");
        rg.addView(rb1);
        rg.addView(rb2);
        layout.addView(rg);

        builder.setView(layout);
        builder.setPositiveButton("授权并启动", (d, w) -> {
            String s = etAmt.getText().toString();
            if (!s.isEmpty()) {
                managedBetAmount = new BigDecimal(s);
                isAiManaged = true;
                Toast.makeText(this, "🚀 AI 托管已激活，系统转入后台盯盘...", Toast.LENGTH_LONG).show();
                updateAiManageUI();
                performAiBackgroundAnalysis();
            } else {
                Toast.makeText(this, "请输入资金限制", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void performAiBackgroundAnalysis() {
        if (currentGameData == null) return;
        lastAiCheckTime = System.currentTimeMillis();

        new Thread(() -> {
            try {
                StringBuilder prompt = new StringBuilder();
                prompt.append("【托管分析任务】当前博弈池「").append(currentGameData.desc).append("」。\n");
                prompt.append("各选项当前价格如下：\n");

                BigDecimal totalVirtualBd = BigDecimal.ZERO;
                for (int i = 0; i < currentGameData.optionCount; i++) {
                    totalVirtualBd = totalVirtualBd.add(new BigDecimal(currentGameData.virtualReserves.get(i)));
                }

                for (int i = 0; i < currentGameData.optionCount; i++) {
                    float prob = new BigDecimal(currentGameData.virtualReserves.get(i)).divide(totalVirtualBd, 4, RoundingMode.HALF_UP).floatValue();
                    String optName = (i < currentGameData.optionNames.size()) ? currentGameData.optionNames.get(i) : "选项 " + i;
                    prompt.append("选项 ").append(i).append(" [").append(optName).append("] 价格: ").append(prob).append("\n");
                }

                prompt.append("如果发现由于市场情绪导致的显著定价错误(胜率被严重低估)，请在末尾严格输出指令块：AGENT_INTENT:{\"action\":\"MARKET_BUY\",\"optionId\":数字,\"reason\":\"理由\"}\n");
                prompt.append("如果没有绝对把握，请直接回复 无需操作。");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", prompt.toString());

                JSONArray messages = new JSONArray();
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "deepseek-chat");
                requestBody.put("messages", messages);
                requestBody.put("stream", false);

                URL url = new URL("https://api.deepseek.com/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + DEEPSEEK_API_KEY);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() == 200) {
                    Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                    String resStr = scanner.hasNext() ? scanner.next() : "";
                    JSONObject responseJson = new JSONObject(resStr);
                    String aiReply = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                    Pattern pattern = Pattern.compile("AGENT_INTENT:(\\{.*\\})", Pattern.DOTALL);
                    Matcher matcher = pattern.matcher(aiReply);
                    if (matcher.find() && isAiManaged) {
                        JSONObject intent = new JSONObject(matcher.group(1));
                        if (intent.has("action") && intent.getString("action").equals("MARKET_BUY")) {
                            int optionId = intent.getInt("optionId");
                            String reason = intent.has("reason") ? intent.getString("reason") : "发现套利空间";

                            runOnUiThread(() -> {
                                if (isDestroyed() || isFinishing()) return;
                                Toast.makeText(this, "🤖 AI 托管触发：自动买入 [选项" + optionId + "]，理由：" + reason, Toast.LENGTH_LONG).show();
                                BigInteger wei = org.web3j.utils.Convert.toWei(managedBetAmount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();
                                Function f = new Function("buyShares", Arrays.asList(new Uint256(currentGameData.id), new Uint8(optionId)), Collections.emptyList());
                                executeTx(wei, f, "🤖 AI 智能托管订单执行成功！");
                            });
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void renderPendingOrdersUI(LinearLayout llOptions) {
        if (activeLimitOrders.isEmpty()) return;

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📋 运行中的限价委托单 (本地监控中)");
        tvTitle.setTextColor(0xFF0052FF);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        tvTitle.setPadding(padding, padding * 2, padding, padding);
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        llOptions.addView(tvTitle);

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

            MaterialButton btnCancel = new MaterialButton(this);
            btnCancel.setText("撤单");
            btnCancel.setTextColor(0xFFEF4444);
            btnCancel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnCancel.setElevation(0f);
            btnCancel.setRippleColor(android.content.res.ColorStateList.valueOf(0x22EF4444));

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

    private void showOrderDialog(int gameId, int optionId, String optName, boolean isYes, float currentPrice) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isYes ? "买入看涨份额 (Buy Yes)" : "买入看跌份额 (Buy No)");

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

        RadioGroup rgMode = new RadioGroup(this);
        rgMode.setOrientation(LinearLayout.HORIZONTAL);
        rgMode.setPadding(0, 0, 0, padding / 2);

        RadioButton rbMarket = new RadioButton(this);
        rbMarket.setText("市价单 (Market)   ");
        rbMarket.setId(View.generateViewId());

        RadioButton rbLimit = new RadioButton(this);
        rbLimit.setText("限价单 (Limit)");
        rbLimit.setId(View.generateViewId());

        rgMode.addView(rbMarket);
        rgMode.addView(rbLimit);
        layout.addView(rgMode);

        final EditText etAmount = new EditText(this);
        etAmount.setHint("输入买入总金额 (BKC)");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setBackgroundResource(android.R.drawable.edit_text);
        layout.addView(etAmount);

        final EditText etLimitPrice = new EditText(this);
        etLimitPrice.setHint(String.format("当单价低于多少时买入？(需 < %.2f)", currentPrice));
        etLimitPrice.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etLimitPrice.setBackgroundResource(android.R.drawable.edit_text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, padding / 2, 0, 0);
        etLimitPrice.setLayoutParams(lp);
        etLimitPrice.setVisibility(View.GONE);
        layout.addView(etLimitPrice);

        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rbMarket.getId()) {
                etLimitPrice.setVisibility(View.GONE);
            } else if (checkedId == rbLimit.getId()) {
                etLimitPrice.setVisibility(View.VISIBLE);
            }
        });

        rbMarket.setChecked(true);

        builder.setView(layout);

        builder.setPositiveButton("确认", (dialog, which) -> {
            boolean isMarketOrder = rbMarket.isChecked();
            String amtStr = etAmount.getText().toString().trim();
            String limitStr = etLimitPrice.getText().toString().trim();

            if (!amtStr.isEmpty()) {
                try {
                    BigDecimal amount = new BigDecimal(amtStr);
                    BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();

                    if (isMarketOrder) {
                        Function f = new Function("buyShares", Arrays.asList(new Uint256(gameId), new Uint8(optionId)), Collections.emptyList());
                        executeTx(wei, f, "市价单执行成功，份额已发放！");
                    } else {
                        if (limitStr.isEmpty()) {
                            Toast.makeText(this, "请填写触发限价！", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        float limitPrice = Float.parseFloat(limitStr);

                        if (limitPrice >= currentPrice) {
                            Toast.makeText(this, "⚠️ 限价必须低于当前实时单价。如需立刻买入请使用【市价单】", Toast.LENGTH_LONG).show();
                            return;
                        }

                        LimitOrder order = new LimitOrder();
                        order.optionId = optionId;
                        order.optName = optName;
                        order.targetPrice = limitPrice;
                        order.amountWei = wei;
                        order.isProcessing = false;

                        activeLimitOrders.add(order);
                        Toast.makeText(this, "✅ 挂单成功！系统将在后台帮您盯盘", Toast.LENGTH_SHORT).show();
                        if (currentGameData != null) renderDetail(currentGameData);
                    }

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
        final long txStartTime = System.currentTimeMillis();
        android.util.Log.d("LatencyTest", "🚀 发起链上交易，开始计时...");

        runOnUiThread(() -> Toast.makeText(this, "⏳ 正在连接底层公链...", Toast.LENGTH_SHORT).show());

        repository.sendTransaction(value, f, successMsg, new Web3Repository.TxCallback() {
            @Override
            public void onTxSent(String txHash) {
                long sendLatency = System.currentTimeMillis() - txStartTime;
                android.util.Log.d("LatencyTest", "📡 交易网络握手完成，耗时: " + sendLatency + " ms");
            }

            @Override
            public void onConfirmed(String message) {
                long confirmLatency = System.currentTimeMillis() - txStartTime;
                android.util.Log.d("LatencyTest", "✅ 区块已打包！端到端总确认时延: " + confirmLatency + " ms");

                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    String latencyInfo = String.format(" (确认时延: %.2f 秒)", confirmLatency / 1000.0);
                    Toast.makeText(GameDetailActivity.this, message + latencyInfo, Toast.LENGTH_LONG).show();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> loadMarketData(), 1000);
                });
            }

            @Override
            public void onError(String error) {
                long errorLatency = System.currentTimeMillis() - txStartTime;
                android.util.Log.d("LatencyTest", "❌ 交易被拒，中止时延: " + errorLatency + " ms");

                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    Toast.makeText(GameDetailActivity.this, (error != null ? error : "交易被网络拒绝"), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}