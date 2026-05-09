package com.example.testing;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【AI Agent 意图驱动交易终端 (Intent-Centric Trading)】
 * 架构创新：
 * 1. 结构化提示词工程：强迫大模型输出 JSON 格式的执行意图。
 * 2. 意图嗅探与 UI 动态渲染：拦截底层 JSON 指令，转化为用户友好的“一键授权”按钮。
 * 3. 链上交易直达：打通 Web3Repository，实现从“自然语言分析”到“智能合约调用”的无缝闭环。
 */
public class AiAnalysisActivity extends AppCompatActivity {

    private LinearLayout llChatContainer;
    private ScrollView svChat;
    private EditText etInput;
    private ImageButton btnSend;

    // 核心 Web3 交易组件
    private Web3Repository repository;
    private int targetGameId;
    private String privateKey;

    // ⚠️ 极其重要：你的 DeepSeek API KEY
    private static final String DEEPSEEK_API_KEY = "sk-679c615e26234c67b00677ba689a80d8";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    private JSONArray messageHistory = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_analysis);

        // 🌟 初始化 Web3 仓库，为 AI Agent 注入执行交易的能力
        privateKey = getIntent().getStringExtra("PRIVATE_KEY");
        targetGameId = getIntent().getIntExtra("GAME_ID", -1);
        if (privateKey != null) {
            repository = new Web3Repository(privateKey);
        }

        llChatContainer = findViewById(R.id.ll_chat_container);
        svChat = findViewById(R.id.sv_chat);
        etInput = findViewById(R.id.et_chat_input);
        btnSend = findViewById(R.id.btn_send);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // ==========================================
        // 🌟 核心升级：为 DeepSeek 注入 AI Agent 行动派人设
        // ==========================================
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");

            String agentPrompt = "你是一个集成在预测市场中的高级 AI 自动化交易智能体(AI Agent)。\n" +
                    "【任务】: 分析用户提供的盘口数据，判断是否存在预期收益率(EV)的套利空间，并给出操作指令。\n" +
                    "【强制输出规范】: 你的回答必须严格分为两部分：\n" +
                    "第一部分 (文字研报)：用 Markdown 格式进行专业的逻辑推演和基本面分析。\n" +
                    "第二部分 (机器意图)：如果你认为某个选项值得买入，你必须在回答的最末尾(另起一行)附带一段用于程序解析的JSON指令，格式如下（绝对不要用 ```json 语法块包裹）：\n" +
                    "AGENT_INTENT:{\"action\":\"MARKET_BUY\",\"optionId\":数字索引,\"targetPrice\":建议单价,\"reason\":\"简短理由\"}\n" +
                    "注意：如果你建议抄底，可以将 action 设为 LIMIT_BUY。如果你认为目前没有套利空间建议观望，则无需输出 AGENT_INTENT 指令块。";

            systemMsg.put("content", agentPrompt);
            messageHistory.put(systemMsg);
        } catch (Exception e) {
            e.printStackTrace();
        }

        String initialPrompt = getIntent().getStringExtra("INITIAL_PROMPT");
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            addMessageToUI(initialPrompt, true);
            callDeepSeekApi(initialPrompt);
        }

        btnSend.setOnClickListener(v -> {
            String userText = etInput.getText().toString().trim();
            if (!TextUtils.isEmpty(userText)) {
                etInput.setText("");
                addMessageToUI(userText, true);
                callDeepSeekApi(userText);
            }
        });
    }

    /**
     * 将消息渲染到界面的气泡中 (支持意图指令拦截)
     */
    private void addMessageToUI(String text, boolean isUser) {
        View bubbleView = getLayoutInflater().inflate(R.layout.item_chat_bubble, llChatContainer, false);

        LinearLayout llUser = bubbleView.findViewById(R.id.ll_user_message);
        LinearLayout llAi = bubbleView.findViewById(R.id.ll_ai_message);
        TextView tvUser = bubbleView.findViewById(R.id.tv_user_text);
        TextView tvAi = bubbleView.findViewById(R.id.tv_ai_text);

        if (isUser) {
            llUser.setVisibility(View.VISIBLE);
            tvUser.setText(text);
        } else {
            llAi.setVisibility(View.VISIBLE);

            // ==========================================
            // 🌟 创新点：AI 意图解析器 (Intent Parser)
            // 作用：利用正则剥离 JSON 指令，转化为动态 UI 组件，不让用户看到底层代码
            // ==========================================
            String cleanText = text;
            JSONObject intentObj = null;

            try {
                // 使用正则匹配 AGENT_INTENT:{...} 结构
                Pattern pattern = Pattern.compile("AGENT_INTENT:(\\{.*\\})", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String jsonStr = matcher.group(1);
                    intentObj = new JSONObject(jsonStr);
                    // 将 JSON 指令从前端显示的文字中彻底抹除
                    cleanText = text.replace(matcher.group(0), "").trim();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 渲染纯净的 Markdown 研报文字
            tvAi.setText(parseMarkdown(cleanText));

            // 如果捕获到了交易意图，动态生成一个授权执行按钮
            if (intentObj != null && repository != null) {
                final JSONObject finalIntent = intentObj;
                MaterialButton btnExecute = new MaterialButton(this);
                btnExecute.setText("⚡ 授权 AI 自动执行策略");
                btnExecute.setAllCaps(false);
                btnExecute.setBackgroundColor(0xFF0052FF); // 品牌蓝
                btnExecute.setTextColor(android.graphics.Color.WHITE);
                btnExecute.setElevation(0f);
                btnExecute.setCornerRadius((int) (8 * getResources().getDisplayMetrics().density));

                int dp = (int) getResources().getDisplayMetrics().density;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                // 左边距 40dp：留出头像(32dp)+间距(8dp)的空间，让按钮与对话卡片左对齐
                lp.setMargins(40 * dp, 12 * dp, 48 * dp, 0);
                btnExecute.setLayoutParams(lp);

                // 绑定点击事件，呼出交易授权框
                btnExecute.setOnClickListener(v -> showAgentConfirmDialog(finalIntent));

                // 将按钮动态挂载到气泡的最下方
                llAi.addView(btnExecute);
            }
        }

        llChatContainer.addView(bubbleView);
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 呼出 AI Agent 交易授权确认弹窗 (支持用户自定义微调参数)
     */
    private void showAgentConfirmDialog(JSONObject intent) {
        try {
            // 1. 解析 AI 预生成的初始参数
            String aiAction = intent.getString("action"); // MARKET_BUY 或 LIMIT_BUY
            int optionId = intent.getInt("optionId");
            double aiPrice = intent.getDouble("targetPrice");
            String reason = intent.getString("reason");

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("🤖 AI 策略授权与参数微调");

            // 动态构建自定义表单布局
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            // 2. 显示 AI 的分析逻辑 (只读)
            TextView tvReason = new TextView(this);
            tvReason.setText("💡 AI 推荐理由:\n" + reason + "\n\n🎯 目标标的: 选项 " + optionId);
            tvReason.setTextColor(0xFF0F172A);
            tvReason.setTextSize(14f);
            tvReason.setPadding(0, 0, 0, padding);
            layout.addView(tvReason);

            // 3. 交易模式切换 (🌟 允许用户随时推翻 AI 的决定，切换市价/限价)
            TextView tvModeLabel = new TextView(this);
            tvModeLabel.setText("您可在此微调交易模式与价格:");
            tvModeLabel.setTextColor(0xFF64748B);
            tvModeLabel.setTextSize(12f);
            layout.addView(tvModeLabel);

            RadioGroup rgMode = new RadioGroup(this);
            rgMode.setOrientation(LinearLayout.HORIZONTAL);
            RadioButton rbMarket = new RadioButton(this);
            rbMarket.setText("市价急速买入  ");
            rbMarket.setId(View.generateViewId());
            RadioButton rbLimit = new RadioButton(this);
            rbLimit.setText("限价埋伏抄底");
            rbLimit.setId(View.generateViewId());
            rgMode.addView(rbMarket);
            rgMode.addView(rbLimit);
            layout.addView(rgMode);

            // 4. 限价输入框 (🌟 预填充 AI 建议的价格，但允许用户手动修改)
            final EditText etLimitPrice = new EditText(this);
            etLimitPrice.setHint("触发限价 (BKC)");
            etLimitPrice.setText(String.valueOf(aiPrice)); // 填入 AI 的建议价
            etLimitPrice.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etLimitPrice.setBackgroundResource(android.R.drawable.edit_text);
            LinearLayout.LayoutParams lpPrice = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpPrice.setMargins(0, 16, 0, 0);
            etLimitPrice.setLayoutParams(lpPrice);
            layout.addView(etLimitPrice);

            // 根据 AI 的初始建议，自动勾选对应的模式
            if (aiAction.equals("MARKET_BUY")) {
                rbMarket.setChecked(true);
                etLimitPrice.setVisibility(View.GONE); // 市价单隐藏限价框
            } else {
                rbLimit.setChecked(true);
                etLimitPrice.setVisibility(View.VISIBLE);
            }

            // 监听用户的手动切换
            rgMode.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == rbMarket.getId()) {
                    etLimitPrice.setVisibility(View.GONE);
                } else {
                    etLimitPrice.setVisibility(View.VISIBLE);
                }
            });

            // 5. 投入金额输入框 (留给用户做最终的资金决定)
            final EditText etAmount = new EditText(this);
            etAmount.setHint("请输入授权执行的金额 (BKC)");
            etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etAmount.setBackgroundResource(android.R.drawable.edit_text);
            LinearLayout.LayoutParams lpAmt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpAmt.setMargins(0, 32, 0, 0);
            etAmount.setLayoutParams(lpAmt);
            layout.addView(etAmount);

            builder.setView(layout);

            builder.setPositiveButton("签名并执行", (dialog, which) -> {
                String amtStr = etAmount.getText().toString().trim();
                if (amtStr.isEmpty()) {
                    Toast.makeText(this, "授权金额不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 🌟 获取用户最终敲定的参数 (可能是 AI 建议的，也可能是用户篡改后的)
                String finalAction = rbMarket.isChecked() ? "MARKET_BUY" : "LIMIT_BUY";
                double finalPrice = aiPrice;
                if (rbLimit.isChecked()) {
                    String priceStr = etLimitPrice.getText().toString().trim();
                    if (!priceStr.isEmpty()) {
                        finalPrice = Double.parseDouble(priceStr);
                    }
                }

                // 提交给执行引擎
                executeAgentTrade(finalAction, optionId, finalPrice, new BigDecimal(amtStr));
            });

            builder.setNegativeButton("取消", null);
            builder.show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "解析 AI 意图失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Agent 自动调用区块链底层网络发起交易
     */
    private void executeAgentTrade(String action, int optionId, double price, BigDecimal amount) {
        Toast.makeText(this, "🤖 正在构建链上指令...", Toast.LENGTH_SHORT).show();

        BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();

        if (action.equals("MARKET_BUY")) {
            // 一键市价上链
            Function f = new Function("buyShares", Arrays.asList(new Uint256(targetGameId), new Uint8(optionId)), Collections.emptyList());
            repository.sendTransaction(wei, f, "策略执行成功", new Web3Repository.TxCallback() {
                @Override
                public void onTxSent(String txHash) {
                }

                @Override
                public void onConfirmed(String message) {
                    addMessageToUI("✅ **[Agent 监控日志]** 交易已在区块链上被矿工确认。份额已发放至您的钱包，可返回详情页查看最新持仓。", false);
                }

                @Override
                public void onError(String error) {
                    addMessageToUI("❌ **[Agent 错误日志]** 智能合约拒绝了本次交易，原因: " + error, false);
                }
            });
        } else {
            // 限价单属于本地挂单簿功能，此处优雅提示用户退回上一页完成限价挂单
            addMessageToUI("💡 **[Agent 策略提示]** 这是一个【限价抄底策略】。为了保证您的资金安全，请点击左上角返回详情页，在买入面板中选择“限价单”，并填入建议价格 `" + price + "` BKC，让后台机器人帮您盯盘。", false);
        }
    }

    private void callDeepSeekApi(String userText) {
        View loadingBubble = getLayoutInflater().inflate(R.layout.item_chat_bubble, llChatContainer, false);
        loadingBubble.findViewById(R.id.ll_ai_message).setVisibility(View.VISIBLE);
        TextView tvLoading = loadingBubble.findViewById(R.id.tv_ai_text);
        tvLoading.setText("正在进行数据建模与策略回测...");
        llChatContainer.addView(loadingBubble);
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));

        btnSend.setEnabled(false);

        AppExecutors.getInstance().networkIO().execute(() -> {
            // 【测试代码开始】记录大模型请求开始时间
            long aiStartTime = System.currentTimeMillis();
            android.util.Log.d("PerformanceTest", "🤖 开始调用 DeepSeek API...");
            // 【测试代码结束】

            try {
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userText);
                messageHistory.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "deepseek-chat");
                requestBody.put("messages", messageHistory);
                requestBody.put("stream", false);

                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + DEEPSEEK_API_KEY);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                    String resStr = scanner.hasNext() ? scanner.next() : "";
                    JSONObject responseJson = new JSONObject(resStr);

                    String aiReply = responseJson.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");

                    JSONObject aiMsg = new JSONObject();
                    aiMsg.put("role", "assistant");
                    aiMsg.put("content", aiReply);
                    messageHistory.put(aiMsg);

                    AppExecutors.getInstance().mainThread().execute(() -> {
                        llChatContainer.removeView(loadingBubble);
                        addMessageToUI(aiReply, false);
                        btnSend.setEnabled(true);

                        // 【测试代码开始】计算并输出 AI 响应时间
                        long aiTotalTime = System.currentTimeMillis() - aiStartTime;
                        android.util.Log.d("PerformanceTest", "✅ AI 回复渲染完成！总耗时: " + aiTotalTime + " ms");
                        Toast.makeText(AiAnalysisActivity.this, "AI 思考耗时: " + (aiTotalTime / 1000.0) + " 秒", Toast.LENGTH_SHORT).show();
                        // 【测试代码结束】
                    });
                } else {
                    InputStream es = conn.getErrorStream();
                    String errorMsg = es != null ? new Scanner(es, "UTF-8").useDelimiter("\\A").next() : "未知网络错误";
                    AppExecutors.getInstance().mainThread().execute(() -> {
                        llChatContainer.removeView(loadingBubble);
                        addMessageToUI("API 调用失败，状态码: " + responseCode + "\n" + errorMsg, false);
                        btnSend.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> {
                    llChatContainer.removeView(loadingBubble);
                    addMessageToUI("网络出现异常: " + e.getMessage(), false);
                    btnSend.setEnabled(true);
                });
            }
        });
    }

    private android.text.Spanned parseMarkdown(String markdown) {
        if (markdown == null) return android.text.Html.fromHtml("");
        String html = markdown;

        html = html.replaceAll("(?m)^###\\s+(.*)$", "<h3><font color='#0052FF'>$1</font></h3>");
        html = html.replaceAll("(?m)^##\\s+(.*)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^#\\s+(.*)$", "<h1>$1</h1>");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("(?m)^\\s*[\\*\\-]\\s+(.*)$", "&#8226; $1");
        html = html.replace("\n", "<br>");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT);
        } else {
            return android.text.Html.fromHtml(html);
        }
    }
}