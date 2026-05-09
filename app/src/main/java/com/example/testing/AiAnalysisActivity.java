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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Agent 意图驱动交易终端
 * - 使用 DeepSeekClient 统一管理 API 调用，移除本地硬编码 Key
 * - 流式输出：AI 回复逐 token 实时打字显示，体验类似 ChatGPT
 * - 意图解析：流式完成后统一解析 AGENT_INTENT，渲染授权按钮
 */
public class AiAnalysisActivity extends AppCompatActivity {

    private LinearLayout llChatContainer;
    private ScrollView svChat;
    private EditText etInput;
    private ImageButton btnSend;

    private Web3Repository repository;
    private int targetGameId;
    private String privateKey;

    private JSONArray messageHistory = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_analysis);

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

        String initialPrompt = getIntent().getStringExtra("INITIAL_PROMPT");

        if (!DeepSeekClient.isConfigured()) {
            etInput.setEnabled(false);
            btnSend.setEnabled(false);
            etInput.setHint("请先配置 DeepSeek API Key");
            addMessageToUI("🔑 尚未配置 DeepSeek API Key。\n\n请前往「我的」页面 → 配置 API Key，然后重新进入此页面。\n\n你可以在 platform.deepseek.com 免费注册并获取 Key。", false);
            return;
        }

        // 禁用输入，先抓实时行情再构建 system prompt
        btnSend.setEnabled(false);
        etInput.setEnabled(false);
        etInput.setHint("正在获取实时行情数据...");

        initWithMarketData(initialPrompt);

        btnSend.setOnClickListener(v -> {
            String userText = etInput.getText().toString().trim();
            if (!TextUtils.isEmpty(userText)) {
                etInput.setText("");
                addUserBubble(userText);
                callDeepSeekApi(userText);
            }
        });
    }

    private void initWithMarketData(String initialPrompt) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            double[] gold = GoldAdvisoryManager.fetchGoldWithChange();
            double cny = GoldAdvisoryManager.fetchUsdCny();

            String marketContext = buildMarketContext(gold[0], gold[1], cny);

            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;

                try {
                    JSONObject systemMsg = new JSONObject();
                    systemMsg.put("role", "system");
                    systemMsg.put("content",
                            "你是一个集成在黄金票据预测市场中的高级 AI 自动化交易智能体(AI Agent)。\n" +
                            marketContext +
                            "【任务】: 结合实时行情，分析当前博弈池盘口数据，判断是否存在预期收益率(EV)的套利空间，并给出操作指令。\n" +
                            "【强制输出规范】: 你的回答必须严格分为两部分：\n" +
                            "第一部分 (文字研报)：用 Markdown 格式进行专业的逻辑推演和基本面分析。\n" +
                            "第二部分 (机器意图)：如果你认为某个选项值得买入，你必须在回答的最末尾(另起一行)附带一段用于程序解析的JSON指令，格式如下（绝对不要用 ```json 语法块包裹）：\n" +
                            "AGENT_INTENT:{\"action\":\"MARKET_BUY\",\"optionId\":数字索引,\"targetPrice\":建议单价,\"reason\":\"简短理由\"}\n" +
                            "注意：如果你建议抄底，可以将 action 设为 LIMIT_BUY。如果你认为目前没有套利空间建议观望，则无需输出 AGENT_INTENT 指令块。");
                    messageHistory.put(systemMsg);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                etInput.setEnabled(true);
                btnSend.setEnabled(true);
                etInput.setHint("输入问题或粘贴盘口数据...");

                if (initialPrompt != null && !initialPrompt.isEmpty()) {
                    addUserBubble(initialPrompt);
                    callDeepSeekApi(initialPrompt);
                }
            });
        });
    }

    private String buildMarketContext(double price, double change, double cny) {
        if (price <= 0 && cny <= 0) return "";
        StringBuilder sb = new StringBuilder("【实时行情背景】\n");
        if (price > 0) sb.append(String.format("• XAU/USD：$%.2f（24h %+.2f%%）\n", price, change));
        if (cny > 0)   sb.append(String.format("• USD/CNY：%.4f\n", cny));
        sb.append("\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // 核心：流式调用 DeepSeek，实时更新气泡内容
    // ---------------------------------------------------------------

    private void callDeepSeekApi(String userText) {
        try {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);
            messageHistory.put(userMsg);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 创建空 AI 气泡，准备流式填充
        View bubbleView = getLayoutInflater().inflate(R.layout.item_chat_bubble, llChatContainer, false);
        LinearLayout llAi = bubbleView.findViewById(R.id.ll_ai_message);
        TextView tvAi = bubbleView.findViewById(R.id.tv_ai_text);
        llAi.setVisibility(View.VISIBLE);
        tvAi.setText("▌");
        llChatContainer.addView(bubbleView);
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
        btnSend.setEnabled(false);

        long startTime = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();
        // 限流：最多每 80ms 刷新一次 TextView，避免每个 token 都触发 UI 重绘导致闪烁
        final android.os.Handler streamHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] updatePending = {false};

        DeepSeekClient.chatStream(messageHistory, new DeepSeekClient.StreamCallback() {
            @Override
            public void onToken(String token) {
                buffer.append(token);
                if (!updatePending[0]) {
                    updatePending[0] = true;
                    streamHandler.postDelayed(() -> {
                        tvAi.setText(buffer.toString());
                        updatePending[0] = false;
                    }, 80);
                }
            }

            @Override
            public void onComplete(String fullText) {
                // 取消可能还在 pending 的限流更新
                streamHandler.removeCallbacksAndMessages(null);
                try {
                    JSONObject aiMsg = new JSONObject();
                    aiMsg.put("role", "assistant");
                    aiMsg.put("content", fullText);
                    messageHistory.put(aiMsg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                // 流式结束：渲染 Markdown + 解析意图按钮
                renderAiMessage(llAi, tvAi, fullText);
                btnSend.setEnabled(true);

                long elapsed = System.currentTimeMillis() - startTime;
                Toast.makeText(AiAnalysisActivity.this,
                        "AI 思考耗时: " + (elapsed / 1000.0) + " 秒", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                tvAi.setText("网络出现异常: " + error);
                btnSend.setEnabled(true);
            }
        });
    }

    // ---------------------------------------------------------------
    // 渲染最终 AI 消息：Markdown + 意图按钮（流式完成后 & executeAgentTrade 均复用）
    // ---------------------------------------------------------------

    private void renderAiMessage(LinearLayout llAi, TextView tvAi, String text) {
        String cleanText = text;
        JSONObject intentObj = null;

        try {
            Pattern pattern = Pattern.compile("AGENT_INTENT:(\\{.*\\})", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                intentObj = new JSONObject(matcher.group(1));
                cleanText = text.replace(matcher.group(0), "").trim();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        tvAi.setText(parseMarkdown(cleanText));

        if (intentObj != null && repository != null) {
            final JSONObject finalIntent = intentObj;
            MaterialButton btnExecute = new MaterialButton(this);
            btnExecute.setText("⚡ 授权 AI 自动执行策略");
            btnExecute.setAllCaps(false);
            btnExecute.setBackgroundColor(0xFF0052FF);
            btnExecute.setTextColor(android.graphics.Color.WHITE);
            btnExecute.setElevation(0f);
            btnExecute.setCornerRadius((int) (8 * getResources().getDisplayMetrics().density));

            int dp = (int) getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(40 * dp, 12 * dp, 48 * dp, 0);
            btnExecute.setLayoutParams(lp);
            btnExecute.setOnClickListener(v -> showAgentConfirmDialog(finalIntent));
            llAi.addView(btnExecute);
        }
    }

    // ---------------------------------------------------------------
    // UI 工具方法
    // ---------------------------------------------------------------

    private void addUserBubble(String text) {
        View bubbleView = getLayoutInflater().inflate(R.layout.item_chat_bubble, llChatContainer, false);
        bubbleView.findViewById(R.id.ll_user_message).setVisibility(View.VISIBLE);
        ((TextView) bubbleView.findViewById(R.id.tv_user_text)).setText(text);
        llChatContainer.addView(bubbleView);
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
    }

    /** 供 executeAgentTrade 使用：添加非流式 AI 系统消息气泡 */
    private void addMessageToUI(String text, boolean isUser) {
        if (isUser) {
            addUserBubble(text);
            return;
        }
        View bubbleView = getLayoutInflater().inflate(R.layout.item_chat_bubble, llChatContainer, false);
        LinearLayout llAi = bubbleView.findViewById(R.id.ll_ai_message);
        TextView tvAi = bubbleView.findViewById(R.id.tv_ai_text);
        llAi.setVisibility(View.VISIBLE);
        renderAiMessage(llAi, tvAi, text);
        llChatContainer.addView(bubbleView);
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------
    // AI Agent 交易授权弹窗
    // ---------------------------------------------------------------

    private void showAgentConfirmDialog(JSONObject intent) {
        try {
            String aiAction = intent.getString("action");
            int optionId = intent.getInt("optionId");
            double aiPrice = intent.getDouble("targetPrice");
            String reason = intent.getString("reason");

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("🤖 AI 策略授权与参数微调");

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            TextView tvReason = new TextView(this);
            tvReason.setText("💡 AI 推荐理由:\n" + reason + "\n\n🎯 目标标的: 选项 " + optionId);
            tvReason.setTextColor(0xFF0F172A);
            tvReason.setTextSize(14f);
            tvReason.setPadding(0, 0, 0, padding);
            layout.addView(tvReason);

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

            final EditText etLimitPrice = new EditText(this);
            etLimitPrice.setHint("触发限价 (BKC)");
            etLimitPrice.setText(String.valueOf(aiPrice));
            etLimitPrice.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etLimitPrice.setBackgroundResource(android.R.drawable.edit_text);
            LinearLayout.LayoutParams lpPrice = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpPrice.setMargins(0, 16, 0, 0);
            etLimitPrice.setLayoutParams(lpPrice);
            layout.addView(etLimitPrice);

            if (aiAction.equals("MARKET_BUY")) {
                rbMarket.setChecked(true);
                etLimitPrice.setVisibility(View.GONE);
            } else {
                rbLimit.setChecked(true);
                etLimitPrice.setVisibility(View.VISIBLE);
            }

            rgMode.setOnCheckedChangeListener((group, checkedId) -> {
                etLimitPrice.setVisibility(checkedId == rbMarket.getId() ? View.GONE : View.VISIBLE);
            });

            final EditText etAmount = new EditText(this);
            etAmount.setHint("请输入授权执行的金额 (BKC)");
            etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etAmount.setBackgroundResource(android.R.drawable.edit_text);
            LinearLayout.LayoutParams lpAmt = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
                String finalAction = rbMarket.isChecked() ? "MARKET_BUY" : "LIMIT_BUY";
                double finalPrice = aiPrice;
                if (rbLimit.isChecked()) {
                    String priceStr = etLimitPrice.getText().toString().trim();
                    if (!priceStr.isEmpty()) finalPrice = Double.parseDouble(priceStr);
                }
                executeAgentTrade(finalAction, optionId, finalPrice, new BigDecimal(amtStr));
            });
            builder.setNegativeButton("取消", null);
            builder.show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "解析 AI 意图失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeAgentTrade(String action, int optionId, double price, BigDecimal amount) {
        Toast.makeText(this, "🤖 正在构建链上指令...", Toast.LENGTH_SHORT).show();
        BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();

        if (action.equals("MARKET_BUY")) {
            Function f = new Function("buyShares",
                    Arrays.asList(new Uint256(targetGameId), new Uint8(optionId)),
                    Collections.emptyList());
            repository.sendTransaction(wei, f, "策略执行成功", new Web3Repository.TxCallback() {
                @Override public void onTxSent(String txHash) {}
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
            addMessageToUI("💡 **[Agent 策略提示]** 这是一个【限价抄底策略】。为了保证您的资金安全，请点击左上角返回详情页，在买入面板中选择「限价单」，并填入建议价格 " + price + " BKC，让后台机器人帮您盯盘。", false);
        }
    }

    // ---------------------------------------------------------------
    // Markdown 简易渲染
    // ---------------------------------------------------------------

    private android.text.Spanned parseMarkdown(String markdown) {
        if (markdown == null) return android.text.Html.fromHtml("");
        String html = markdown;
        html = html.replaceAll("(?m)^###\\s+(.*)$", "<h3><font color='#0052FF'>$1</font></h3>");
        html = html.replaceAll("(?m)^##\\s+(.*)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^#\\s+(.*)$", "<h1>$1</h1>");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("(?m)^\\s*[\\*\\-]\\s+(.*)$", "&#8226; $1");
        html = html.replace("\n", "<br>");
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT);
    }
}
