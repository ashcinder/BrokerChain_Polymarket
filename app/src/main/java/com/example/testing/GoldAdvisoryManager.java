package com.example.testing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 黄金投资顾问引擎
 * 流程：并行获取实时金价(新浪)+汇率 → DeepSeek 综合分析 → 结构化投资建议
 */
public class GoldAdvisoryManager {

    public static class Advisory {
        public String signal = "HOLD";
        public int confidence = 50;
        public double priceUsd = 0;
        public double change24h = 0;
        public double usdCny = 0;
        public String summary = "";
        public List<String> factors = new ArrayList<>();
    }

    public interface AdvisoryCallback {
        void onSuccess(Advisory advisory);
        void onError(String error);
    }

    public static void fetch(AdvisoryCallback callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            // 并行拉取金价和汇率
            AtomicReference<double[]> goldData = new AtomicReference<>(new double[]{0, 0});
            AtomicReference<Double> usdCny = new AtomicReference<>(0.0);
            CountDownLatch latch = new CountDownLatch(2);

            AppExecutors.getInstance().networkIO().execute(() -> {
                try { goldData.set(fetchGoldWithChange()); } finally { latch.countDown(); }
            });
            AppExecutors.getInstance().networkIO().execute(() -> {
                try { usdCny.set(fetchUsdCny()); } finally { latch.countDown(); }
            });

            try { latch.await(); } catch (InterruptedException ignored) {}

            double price = goldData.get()[0];
            double change = goldData.get()[1];
            double cny = usdCny.get();

            JSONArray messages = new JSONArray();
            try {
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", buildPrompt(price, change, cny));
                messages.put(userMsg);
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> callback.onError(e.getMessage()));
                return;
            }

            DeepSeekClient.chat(messages, 0.3, new DeepSeekClient.SimpleCallback() {
                @Override
                public void onSuccess(String reply) {
                    callback.onSuccess(parseAdvisory(reply, price, change, cny));
                }
                @Override
                public void onError(String error) {
                    callback.onError(error);
                }
            });
        });
    }

    // 主源：新浪财经（国内可达）
    static double[] fetchGoldWithChange() {
        try {
            URL url = new URL("https://hq.sinajs.cn/list=hf_XAU");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Referer", "https://finance.sina.com.cn");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream();
                     Scanner sc = new Scanner(is, "GBK").useDelimiter("\\A")) {
                    String body = sc.hasNext() ? sc.next() : "";
                    int start = body.indexOf('"');
                    int end = body.lastIndexOf('"');
                    if (start >= 0 && end > start) {
                        String[] fields = body.substring(start + 1, end).split(",");
                        if (fields.length > 1) {
                            double price = Double.parseDouble(fields[0]);
                            double prevClose = Double.parseDouble(fields[1]);
                            double changePct = (prevClose > 0) ? (price - prevClose) / prevClose * 100 : 0;
                            if (price > 0) return new double[]{price, changePct};
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return new double[]{0, 0};
    }

    static double fetchUsdCny() {
        try {
            URL url = new URL("https://open.er-api.com/v6/latest/USD");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream();
                     Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    String body = sc.hasNext() ? sc.next() : "";
                    return new JSONObject(body).getJSONObject("rates").optDouble("CNY", 0);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String buildPrompt(double price, double change, double cny) {
        String priceInfo = price > 0
                ? String.format("当前黄金现货价格 $%.2f/盎司，24小时涨跌幅 %+.2f%%", price, change)
                : "（实时金价暂时获取失败）";
        String cnyInfo = cny > 0
                ? String.format("当前美元兑人民币汇率 %.4f", cny)
                : "（汇率暂时获取失败）";

        return "你是一位专业的黄金市场分析师，服务于散户投资者。\n" +
                "【实时行情】\n" +
                "• " + priceInfo + "\n" +
                "• " + cnyInfo + "\n\n" +
                "请综合以下维度进行分析，给出简明投资建议：\n" +
                "1. 当前价位与近期走势研判\n" +
                "2. 地缘政治对黄金避险需求的影响\n" +
                "3. 美元走势与通胀预期\n" +
                "4. 央行购金动态与机构资金流向\n\n" +
                "严格按此 JSON 格式输出（不要用代码块包裹）：\n" +
                "GOLD_ADVISORY:{\"signal\":\"BUY或HOLD或SELL\",\"confidence\":置信度0到100," +
                "\"priceUsd\":" + (price > 0 ? price : 0) + "," +
                "\"summary\":\"一句话核心结论（中文，30字以内）\"," +
                "\"factors\":[\"因素1\",\"因素2\",\"因素3\"]}";
    }

    private static Advisory parseAdvisory(String reply, double price, double change, double cny) {
        Advisory a = new Advisory();
        a.priceUsd = price;
        a.change24h = change;
        a.usdCny = cny;
        try {
            Pattern p = Pattern.compile("GOLD_ADVISORY:(\\{.*\\})", Pattern.DOTALL);
            Matcher m = p.matcher(reply);
            if (m.find()) {
                JSONObject json = new JSONObject(m.group(1));
                a.signal = json.optString("signal", "HOLD").toUpperCase();
                a.confidence = Math.max(0, Math.min(100, json.optInt("confidence", 50)));
                a.priceUsd = json.optDouble("priceUsd", price);
                a.summary = json.optString("summary", "");
                JSONArray arr = json.optJSONArray("factors");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) a.factors.add(arr.getString(i));
                }
            } else {
                String upper = reply.toUpperCase();
                if (upper.contains("BUY") || upper.contains("买入") || upper.contains("看多")) a.signal = "BUY";
                else if (upper.contains("SELL") || upper.contains("卖出") || upper.contains("看空")) a.signal = "SELL";
                a.summary = reply.length() > 80 ? reply.substring(0, 80) + "…" : reply;
            }
        } catch (Exception e) {
            a.summary = "结果解析异常，请重试";
        }
        return a;
    }
}
