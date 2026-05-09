package com.example.testing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聚合行情数据：金价+涨跌幅、美元/人民币汇率、AI市场简报（含要闻）
 */
public class MarketDataManager {

    public static class MarketData {
        public double goldPriceUsd = 0;
        public double goldChange24h = 0;   // 百分比，正为涨
        public double usdCny = 0;
        public String sentiment = "NEUTRAL"; // BULLISH / BEARISH / NEUTRAL
        public String brief = "";           // 一段话市场简报
        public List<String> newsItems = new ArrayList<>(); // 要闻条目
    }

    public interface Callback {
        void onSuccess(MarketData data);
        void onError(String error);
    }

    public static void fetch(Callback callback) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            // 并行拉取金价和汇率
            AtomicReference<Double> goldPrice = new AtomicReference<>(0.0);
            AtomicReference<Double> goldChange = new AtomicReference<>(0.0);
            AtomicReference<Double> usdCny = new AtomicReference<>(0.0);
            CountDownLatch latch = new CountDownLatch(2);

            AppExecutors.getInstance().networkIO().execute(() -> {
                try {
                    double[] result = fetchGoldWithChange();
                    goldPrice.set(result[0]);
                    goldChange.set(result[1]);
                } finally {
                    latch.countDown();
                }
            });

            AppExecutors.getInstance().networkIO().execute(() -> {
                try {
                    usdCny.set(fetchUsdCny());
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await();
            } catch (InterruptedException ignored) {}

            // 未配置 API Key 时直接返回行情数据，跳过 DeepSeek 调用
            if (!DeepSeekClient.isConfigured()) {
                MarketData data = new MarketData();
                data.goldPriceUsd = goldPrice.get();
                data.goldChange24h = goldChange.get();
                data.usdCny = usdCny.get();
                data.brief = "🔑 请在「我的」页面配置 DeepSeek API Key 以启用 AI 简报";
                AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(data));
                return;
            }

            // 构造 DeepSeek prompt
            String prompt = buildPrompt(goldPrice.get(), goldChange.get(), usdCny.get());
            JSONArray messages = new JSONArray();
            try {
                JSONObject msg = new JSONObject();
                msg.put("role", "user");
                msg.put("content", prompt);
                messages.put(msg);
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> callback.onError(e.getMessage()));
                return;
            }

            DeepSeekClient.chat(messages, 0.4, new DeepSeekClient.SimpleCallback() {
                @Override
                public void onSuccess(String reply) {
                    MarketData data = parseReply(reply, goldPrice.get(), goldChange.get(), usdCny.get());
                    callback.onSuccess(data);
                }

                @Override
                public void onError(String error) {
                    // DeepSeek失败时仍返回行情数据，简报留空
                    MarketData data = new MarketData();
                    data.goldPriceUsd = goldPrice.get();
                    data.goldChange24h = goldChange.get();
                    data.usdCny = usdCny.get();
                    data.brief = "AI简报暂时不可用";
                    callback.onSuccess(data);
                }
            });
        });
    }

    // 复用 GoldAdvisoryManager 的实现，避免重复网络代码
    private static double[] fetchGoldWithChange() {
        return GoldAdvisoryManager.fetchGoldWithChange();
    }

    private static double fetchUsdCny() {
        return GoldAdvisoryManager.fetchUsdCny();
    }

    private static String buildPrompt(double gold, double change, double cny) {
        String goldInfo = gold > 0
                ? String.format("当前金价 $%.2f/盎司，24小时涨跌幅 %+.2f%%", gold, change)
                : "金价暂时获取失败";
        String cnyInfo = cny > 0
                ? String.format("当前美元兑人民币汇率 %.4f", cny)
                : "汇率暂时获取失败";

        return "你是一位黄金市场分析师，请基于以下实时数据给出简明市场简报：\n" +
                "【金价】" + goldInfo + "\n" +
                "【汇率】" + cnyInfo + "\n\n" +
                "重要：brief 字段中若引用金价或汇率数字，必须与上方数据完全一致，不得四舍五入或改变小数位数。\n\n" +
                "请严格按照以下 JSON 格式输出（不要用代码块包裹）：\n" +
                "MARKET_BRIEF:{\"sentiment\":\"BULLISH或BEARISH或NEUTRAL\"," +
                "\"brief\":\"一段话市场分析，60字以内，中文\"," +
                "\"news\":[\"要闻1，15字以内\",\"要闻2，15字以内\",\"要闻3，15字以内\"]}";
    }

    private static MarketData parseReply(String reply, double gold, double change, double cny) {
        MarketData data = new MarketData();
        data.goldPriceUsd = gold;
        data.goldChange24h = change;
        data.usdCny = cny;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("MARKET_BRIEF:(\\{.*\\})", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(reply);
            if (m.find()) {
                JSONObject json = new JSONObject(m.group(1));
                data.sentiment = json.optString("sentiment", "NEUTRAL").toUpperCase();
                data.brief = json.optString("brief", "");
                JSONArray arr = json.optJSONArray("news");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) data.newsItems.add(arr.getString(i));
                }
            } else {
                data.brief = reply.length() > 80 ? reply.substring(0, 80) + "…" : reply;
            }
        } catch (Exception e) {
            data.brief = "简报解析异常";
        }
        return data;
    }
}
