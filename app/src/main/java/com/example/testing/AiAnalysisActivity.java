package com.example.testing;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 【DeepSeek AI 交互界面】
 * 作用：接收来自详情页的博弈池数据，拼接成 Prompt，调用 DeepSeek 的大语言模型 API 获取投资建议。
 */
public class AiAnalysisActivity extends AppCompatActivity {

    private LinearLayout llChatContainer;
    private ScrollView svChat;
    private EditText etInput;
    private ImageButton btnSend;

    // ⚠️ 极其重要：你的 DeepSeek API KEY
    private static final String DEEPSEEK_API_KEY = "sk-679c615e26234c67b00677ba689a80d8";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    // 历史对话记录，用于保持上下文
    private JSONArray messageHistory = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_analysis);

        llChatContainer = findViewById(R.id.ll_chat_container);
        svChat = findViewById(R.id.sv_chat);
        etInput = findViewById(R.id.et_chat_input);
        btnSend = findViewById(R.id.btn_send);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // ==========================================
        // 🌟 核心升级：为 DeepSeek 注入 Polymarket / AMM 专用的投研人设 (System Prompt)
        // ==========================================
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");

            // 构建极其专业的 DeFi 预测市场分析师人设
            String systemPrompt = "你是一个资深的 Web3 去中心化金融(DeFi)与预测市场(如 Polymarket)的投研分析师。\n" +
                    "你精通 AMM(自动做市商)机制、恒定乘积公式、条件代币(Conditional Tokens)以及隐含概率(Implied Probability)的定价逻辑。\n" +
                    "接下来，用户会发送一个预测市场的盘口数据（包含主题、规则、总资金池、各选项的虚拟储备量和当前胜率/价格）。\n" +
                    "请结合现实世界的宏观背景、新闻动态，给出专业的投资建议。你的回答必须包含：\n" +
                    "1. 盘口分析：当前各选项的价格(隐含胜率)是否合理反映了现实世界发生的概率？\n" +
                    "2. 基本面研判：简述影响该事件走向的核心因素。\n" +
                    "3. 交易策略：明确指出哪个选项（如 Buy Yes 或 Buy No）存在预期收益率(EV)的套利空间，或者是否被市场情绪高估/低估，并提示流动性风险。\n" +
                    "要求：排版清晰（严格使用 Markdown 多级标题），专业客观，通俗易懂，直击痛点。";

            systemMsg.put("content", systemPrompt);
            messageHistory.put(systemMsg);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 接收从详情页传过来的“初始分析数据”
        String initialPrompt = getIntent().getStringExtra("INITIAL_PROMPT");
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            // 自动将分析请求发给 AI
            addMessageToUI(initialPrompt, true);
            callDeepSeekApi(initialPrompt);
        } else {
            addMessageToUI("你好！我是专注 AMM 预测市场的 DeepSeek 投研助手。请问你想分析哪个盘口？", false);
        }

        // 发送按钮点击事件
        btnSend.setOnClickListener(v -> {
            String userText = etInput.getText().toString().trim();
            if (TextUtils.isEmpty(userText)) return;

            etInput.setText("");
            addMessageToUI(userText, true);
            callDeepSeekApi(userText);
        });
    }

    /**
     * 将消息渲染到界面的气泡中
     *
     * @param text   消息内容
     * @param isUser true 为用户发送（右侧蓝色），false 为 AI 接收（左侧白色）
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
            tvAi.setText(parseMarkdown(text));
        }

        llChatContainer.addView(bubbleView);
        // 自动滚动到底部
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 核心：利用 AppExecutors 后台线程调用 DeepSeek HTTP API
     */
    private void callDeepSeekApi(String userText) {
        // 先在 UI 显示一个 Loading 状态
        View loadingBubble = getLayoutInflater().inflate(R.layout.item_chat_bubble, llChatContainer, false);
        loadingBubble.findViewById(R.id.ll_ai_message).setVisibility(View.VISIBLE);
        TextView tvLoading = loadingBubble.findViewById(R.id.tv_ai_text);
        tvLoading.setText("正在基于 AMM 模型进行深度推演...");
        llChatContainer.addView(loadingBubble);
        svChat.post(() -> svChat.fullScroll(View.FOCUS_DOWN));

        btnSend.setEnabled(false);

        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // 1. 将用户的输入压入历史记录
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userText);
                messageHistory.put(userMsg);

                // 2. 构建 DeepSeek 的请求体 JSON
                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "deepseek-chat"); // 使用 deepseek 官方主模型
                requestBody.put("messages", messageHistory);
                requestBody.put("stream", false);

                // 3. 发送 POST 请求
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + DEEPSEEK_API_KEY);
                conn.setConnectTimeout(10000); // 增加网络超时时间，防止大模型响应过慢导致崩溃
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // 4. 读取解析返回的回复
                    Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                    String resStr = scanner.hasNext() ? scanner.next() : "";
                    JSONObject responseJson = new JSONObject(resStr);

                    // 获取回复文本
                    String aiReply = responseJson.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");

                    // 把 AI 的回复存入上下文
                    JSONObject aiMsg = new JSONObject();
                    aiMsg.put("role", "assistant");
                    aiMsg.put("content", aiReply);
                    messageHistory.put(aiMsg);

                    // 5. 切回主线程更新 UI
                    AppExecutors.getInstance().mainThread().execute(() -> {
                        llChatContainer.removeView(loadingBubble); // 移除 Loading
                        addMessageToUI(aiReply, false);            // 添加真实回复
                        btnSend.setEnabled(true);
                    });
                } else {
                    // 读取失败原因
                    InputStream es = conn.getErrorStream();
                    String errorMsg = es != null ? new Scanner(es, "UTF-8").useDelimiter("\\A").next() : "未知网络错误";
                    AppExecutors.getInstance().mainThread().execute(() -> {
                        llChatContainer.removeView(loadingBubble);
                        addMessageToUI("抱歉，API 调用失败，状态码: " + responseCode + "\n" + errorMsg, false);
                        btnSend.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> {
                    llChatContainer.removeView(loadingBubble);
                    addMessageToUI("抱歉，网络出现异常: " + e.getMessage(), false);
                    btnSend.setEnabled(true);
                });
            }
        });
    }

    /**
     * 原生轻量级 Markdown 解析器 (增强版)
     * 将 DeepSeek 返回的标题(###)、加粗(**)、列表(*)、换行(\n)转化为 Android 原生 Html 渲染
     */
    private android.text.Spanned parseMarkdown(String markdown) {
        if (markdown == null) return android.text.Html.fromHtml("");

        String html = markdown;

        // 1. 处理三级标题 (### 文字) -> 将文字放大并加粗 (模拟 <h3> 效果)
        html = html.replaceAll("(?m)^###\\s+(.*)$", "<h3><font color='#0052FF'>$1</font></h3>");

        // 2. 处理二级标题 (## 文字) -> (模拟 <h2> 效果)
        html = html.replaceAll("(?m)^##\\s+(.*)$", "<h2>$1</h2>");

        // 3. 处理一级标题 (# 文字) -> (模拟 <h1> 效果)
        html = html.replaceAll("(?m)^#\\s+(.*)$", "<h1>$1</h1>");

        // 处理加粗: 将 **文字** 替换为 <b>文字</b>
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");

        // 处理无序列表: 将行首的 * 或 - 替换为原生的圆点符号
        html = html.replaceAll("(?m)^\\s*[\\*\\-]\\s+(.*)$", "&#8226; $1");

        // 最后处理换行: 将剩余的 \n 替换为 HTML 的换行符 <br>
        html = html.replace("\n", "<br>");

        // 调用 Android 原生 Html 解析引擎渲染文本
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT);
        } else {
            return android.text.Html.fromHtml(html);
        }
    }
}