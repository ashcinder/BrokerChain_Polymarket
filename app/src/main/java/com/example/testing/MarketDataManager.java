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

    // 金价 + 24h涨跌幅
    // 主源: 新浪财经 hq.sinajs.cn（国内可达，自带涨跌幅）
    // 备源: 东方财富 push2.eastmoney.com
    private static double[] fetchGoldWithChange() {
        // 主源：新浪财经
        // 返回格式: var hq_str_hf_XAU="现价,昨收,今开,最新,最高,最低,涨跌额,涨跌幅%,..."
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
                    // 提取引号内的数据
                    int start = body.indexOf('"');
                    int end = body.lastIndexOf('"');
                    if (start >= 0 && end > start) {
                        String[] fields = body.substring(start + 1, end).split(",");
                        if (fields.length > 1) {
                            double price = Double.parseDouble(fields[0]);     // 现价
                            double prevClose = Double.parseDouble(fields[1]); // 昨收
                            double changePct = (prevClose > 0)
                                    ? (price - prevClose) / prevClose * 100 : 0;
                            if (price > 0) return new double[]{price, changePct};
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 备源：东方财富（国际金价代码 Au9999）
        try {
            URL url = new URL("https://push2.eastmoney.com/api/qt/stock/get?secid=110.Au9999&fields=f43,f170");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Referer", "https://quote.eastmoney.com");
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream();
                     Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    String body = sc.hasNext() ? sc.next() : "";
                    JSONObject root = new JSONObject(body);
                    JSONObject data = root.optJSONObject("data");
                    if (data != null) {
                        // f43=最新价(×100需除100), f170=涨跌幅(×100需除100)
                        double price = data.optDouble("f43", 0) / 100.0;
                        double changePct = data.optDouble("f170", 0) / 100.0;
                        if (price > 0) return new double[]{price, changePct};
                    }
                }
            }
        } catch (Exception ignored) {}
        return new double[]{0, 0};
    }

    // 美元/人民币汇率
    private static double fetchUsdCny() {
        try {
            URL url = new URL("https://open.er-api.com/v6/latest/USD");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream();
                     Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    String body = sc.hasNext() ? sc.next() : "";
                    JSONObject json = new JSONObject(body);
                    return json.getJSONObject("rates").optDouble("CNY", 0);
                }
            }
        } catch (Exception ignored) {}
        return 0;
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
