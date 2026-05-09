package com.example.testing;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.testing.databinding.ActivityMainBinding;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private Web3Repository repository;
    private List<Web3Repository.GameModel> allGamesList = new ArrayList<>();
    private String privateKey;

    private enum ViewMode {HOME, PORTFOLIO, CREATE, PROFILE}

    private ViewMode currentMode = ViewMode.HOME;

    private static final String DEFAULT_GOLD_IMAGE = null; // 无远程默认图，使用本地占位

    // 锁定到唯一的黄金票据博弈池 ID（创建后从链上查到 ID 填入此处）
    private static final int GOLD_GAME_ID = 0;

    private Uri selectedImageUri = null;
    private String selectedImageUrl = null;   // 直接输入的 URL（优先于本地图片）
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private OracleDaemonManager oracleManager;
    private boolean advisoryLoaded = false;
    private boolean marketBriefLoaded = false;
    private Web3Repository.GameModel goldGame = null;
    private MarketDataManager.MarketData latestMarketData = null;

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

        initImagePicker();
        setupNavigation();
        setupProfile();
        setupCreateTabLogic();
        setupUI();
        setupGoldAdvisory();
        setupMarketBrief();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (oracleManager != null) {
            oracleManager.destroy();
        }
    }

    private void initImagePicker() {
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        selectedImageUri = result.getData().getData();
                        selectedImageUrl = null;
                        ImageView ivSelected = findViewById(R.id.iv_selected_avatar);
                        if (ivSelected != null) {
                            ivSelected.setImageURI(selectedImageUri);
                            ivSelected.clearColorFilter();
                        }
                    }
                });
    }

    private String getBase64FromUri(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            byte[] bytes = baos.toByteArray();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static void loadNetworkImage(String urlStr, ImageView imageView) {
        if (urlStr == null || urlStr.isEmpty() || !urlStr.startsWith("http")) return;
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                InputStream in = new URL(urlStr).openStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                AppExecutors.getInstance().mainThread().execute(() -> imageView.setImageBitmap(bmp));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
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

        if (mode == ViewMode.HOME) binding.tvTopTitle.setText("黄金票据市场");
        else if (mode == ViewMode.PORTFOLIO) binding.tvTopTitle.setText("我的持仓");

        // 切到首页时若未加载行情简报则触发一次
        if (mode == ViewMode.HOME && !marketBriefLoaded) {
            marketBriefLoaded = true;
            loadMarketBrief();
        }

        // 切到「我的」时若还未加载情报则触发一次
        if (mode == ViewMode.PROFILE && !advisoryLoaded) {
            advisoryLoaded = true;
            loadGoldAdvisory();
        }

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

    private void setupGoldAdvisory() {
        android.widget.LinearLayout llFactors = findViewById(R.id.ll_gold_factors);
        com.google.android.material.button.MaterialButton btnRefresh = findViewById(R.id.btn_refresh_advisory);
        if (btnRefresh == null) return;

        btnRefresh.setOnClickListener(v -> loadGoldAdvisory());

        // 首次进入「我的」页时自动加载
        if (!advisoryLoaded) {
            advisoryLoaded = true;
            loadGoldAdvisory();
        }
    }

    private void loadGoldAdvisory() {
        android.widget.TextView tvPrice = findViewById(R.id.tv_gold_price);
        android.widget.TextView tvSignal = findViewById(R.id.tv_gold_signal);
        android.widget.TextView tvConfidence = findViewById(R.id.tv_gold_confidence);
        android.widget.TextView tvSummary = findViewById(R.id.tv_gold_summary);
        android.widget.LinearLayout llFactors = findViewById(R.id.ll_gold_factors);
        com.google.android.material.button.MaterialButton btnRefresh = findViewById(R.id.btn_refresh_advisory);

        if (tvSummary == null) return;
        tvSummary.setText("正在联网分析市场动态...");
        if (btnRefresh != null) btnRefresh.setEnabled(false);

        GoldAdvisoryManager.fetch(new GoldAdvisoryManager.AdvisoryCallback() {
            @Override
            public void onSuccess(GoldAdvisoryManager.Advisory advisory) {
                if (isDestroyed() || isFinishing()) return;

                if (tvPrice != null) {
                    tvPrice.setText(advisory.priceUsd > 0
                            ? String.format("$%.2f/oz", advisory.priceUsd)
                            : "价格未获取");
                }

                if (tvSignal != null) {
                    tvSignal.setText(advisory.signal);
                    int bgColor;
                    switch (advisory.signal) {
                        case "BUY":  bgColor = 0xFF10B981; break;
                        case "SELL": bgColor = 0xFFEF4444; break;
                        default:     bgColor = 0xFFF59E0B; break;
                    }
                    tvSignal.setBackgroundColor(bgColor);
                }

                if (tvConfidence != null) {
                    tvConfidence.setText("置信度 " + advisory.confidence + "%");
                }

                if (tvSummary != null) {
                    tvSummary.setText(advisory.summary);
                }

                if (llFactors != null) {
                    llFactors.removeAllViews();
                    for (String factor : advisory.factors) {
                        android.widget.TextView tv = new android.widget.TextView(MainActivity.this);
                        tv.setText("• " + factor);
                        tv.setTextColor(0xFF92400E);
                        tv.setTextSize(13f);
                        tv.setPadding(0, 4, 0, 4);
                        llFactors.addView(tv);
                    }
                }

                if (btnRefresh != null) btnRefresh.setEnabled(true);
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                if (tvSummary != null) tvSummary.setText("分析失败，请检查网络后重试");
                if (btnRefresh != null) btnRefresh.setEnabled(true);
            }
        });
    }

    private void setupMarketBrief() {
        com.google.android.material.button.MaterialButton btnRefresh = findViewById(R.id.btn_refresh_brief);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                marketBriefLoaded = false;
                loadMarketBrief();
            });
        }

        // 点击简报卡片整体 → 跳转 AI 分析，带入当前行情
        com.google.android.material.card.MaterialCardView cvBrief = findViewById(R.id.cv_market_brief);
        if (cvBrief != null) {
            cvBrief.setOnClickListener(v -> launchAiWithMarketContext());
        }

        if (!marketBriefLoaded) {
            marketBriefLoaded = true;
            loadMarketBrief();
        }
    }

    private void launchAiWithMarketContext() {
        Intent intent = new Intent(this, AiAnalysisActivity.class);
        intent.putExtra("PRIVATE_KEY", privateKey);
        // 没有锁定到特定游戏时传 -1，AiAnalysisActivity 会以纯顾问模式运行
        intent.putExtra("GAME_ID", goldGame != null ? goldGame.id : -1);

        if (latestMarketData != null) {
            MarketDataManager.MarketData d = latestMarketData;
            String prompt = String.format(
                    "请基于以下实时行情为我做一次深度黄金投研分析：\n" +
                    "• XAU/USD：$%.2f（24h %+.2f%%）\n" +
                    "• USD/CNY：%.4f\n" +
                    "• AI情绪：%s\n" +
                    "• 市场简报：%s\n\n" +
                    "请从技术面、基本面、情绪面三个维度展开，并给出具体的操作建议。",
                    d.goldPriceUsd, d.goldChange24h, d.usdCny,
                    d.sentiment, d.brief);
            intent.putExtra("INITIAL_PROMPT", prompt);
        }
        startActivity(intent);
    }

    private void loadMarketBrief() {
        android.widget.TextView tvBrief = findViewById(R.id.tv_market_brief);
        android.widget.TextView tvGoldPrice = findViewById(R.id.tv_ticker_gold_price);
        android.widget.TextView tvGoldChange = findViewById(R.id.tv_ticker_gold_change);
        android.widget.TextView tvCny = findViewById(R.id.tv_ticker_cny);
        android.widget.TextView tvSentiment = findViewById(R.id.tv_ticker_sentiment);
        android.widget.LinearLayout llNews = findViewById(R.id.ll_news_items);

        if (tvBrief != null) tvBrief.setText("正在获取市场数据...");

        MarketDataManager.fetch(new MarketDataManager.Callback() {
            @Override
            public void onSuccess(MarketDataManager.MarketData data) {
                if (isDestroyed() || isFinishing()) return;
                latestMarketData = data;

                // 行情条
                if (tvGoldPrice != null) {
                    tvGoldPrice.setText(data.goldPriceUsd > 0
                            ? String.format("$%.2f", data.goldPriceUsd) : "--");
                }
                if (tvGoldChange != null) {
                    if (data.goldChange24h != 0) {
                        String sign = data.goldChange24h > 0 ? "+" : "";
                        tvGoldChange.setText(String.format("%s%.2f%%", sign, data.goldChange24h));
                        tvGoldChange.setTextColor(data.goldChange24h > 0 ? 0xFF10B981 : 0xFFEF4444);
                    } else {
                        tvGoldChange.setText("--");
                        tvGoldChange.setTextColor(0xFF64748B);
                    }
                }
                if (tvCny != null) {
                    tvCny.setText(data.usdCny > 0 ? String.format("%.4f", data.usdCny) : "--");
                }
                if (tvSentiment != null) {
                    switch (data.sentiment) {
                        case "BULLISH": tvSentiment.setText("看多 ↑"); tvSentiment.setTextColor(0xFF10B981); break;
                        case "BEARISH": tvSentiment.setText("看空 ↓"); tvSentiment.setTextColor(0xFFEF4444); break;
                        default:        tvSentiment.setText("中性 →"); tvSentiment.setTextColor(0xFF64748B); break;
                    }
                }

                // 简报正文
                if (tvBrief != null) tvBrief.setText(data.brief.isEmpty() ? "暂无简报" : data.brief);

                // 要闻列表
                if (llNews != null) {
                    llNews.removeAllViews();
                    for (String item : data.newsItems) {
                        android.widget.TextView tv = new android.widget.TextView(MainActivity.this);
                        tv.setText("• " + item);
                        tv.setTextColor(0xFF475569);
                        tv.setTextSize(12.5f);
                        tv.setPadding(0, 4, 0, 4);
                        llNews.addView(tv);
                    }
                }
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                if (tvBrief != null) tvBrief.setText("行情获取失败，请检查网络");
            }
        });
    }

    private void setupCreateTabLogic() {
        LinearLayout llSelectAvatar = findViewById(R.id.ll_select_avatar);
        if (llSelectAvatar != null) {
            llSelectAvatar.setOnClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("选择封面图来源")
                        .setItems(new String[]{"从相册选择", "粘贴图片 URL", "使用默认黄金图"}, (dialog, which) -> {
                            if (which == 0) {
                                // ACTION_GET_CONTENT 比 ACTION_PICK 兼容性更好，模拟器也能用
                                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                                intent.setType("image/*");
                                imagePickerLauncher.launch(intent);
                            } else if (which == 1) {
                                EditText etUrl = new EditText(this);
                                etUrl.setHint("https://example.com/gold.jpg");
                                new androidx.appcompat.app.AlertDialog.Builder(this)
                                        .setTitle("输入图片 URL")
                                        .setView(etUrl)
                                        .setPositiveButton("确认", (d, w) -> {
                                            String url = etUrl.getText().toString().trim();
                                            if (!url.isEmpty()) {
                                                selectedImageUrl = url;
                                                selectedImageUri = null;
                                                ImageView iv = findViewById(R.id.iv_selected_avatar);
                                                if (iv != null) {
                                                    loadNetworkImage(url, iv);
                                                    iv.clearColorFilter();
                                                }
                                            }
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                            } else {
                                // 无默认远程图，留空即可，上链时 avatarUrl 为空字符串
                                selectedImageUrl = "";
                                selectedImageUri = null;
                            }
                        })
                        .show();
            });
        }

        MaterialButton btnDeploy = findViewById(R.id.btn_deploy_game);
        if (btnDeploy != null) {
            btnDeploy.setOnClickListener(v -> {
                String desc = ((EditText) findViewById(R.id.et_desc)).getText().toString().trim();
                String cond = ((EditText) findViewById(R.id.et_condition)).getText().toString().trim();
                String optsStr = ((EditText) findViewById(R.id.et_options)).getText().toString().trim();
                String durationStr = ((EditText) findViewById(R.id.et_duration)).getText().toString().trim();
                EditText etDetail = findViewById(R.id.et_detailed_info);
                String detailInfo = etDetail != null ? etDetail.getText().toString().trim() : "";

                if (TextUtils.isEmpty(desc) || TextUtils.isEmpty(cond) || TextUtils.isEmpty(optsStr) || TextUtils.isEmpty(durationStr)) {
                    Toast.makeText(this, "请完整填写主题、规则、选项和时间", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 未选图时 avatarUrl 留空
                if (selectedImageUri == null && selectedImageUrl == null) {
                    selectedImageUrl = "";
                }

                String[] optsArray = optsStr.split(",");
                if (optsArray.length < 2) {
                    Toast.makeText(this, "至少需要2个选项", Toast.LENGTH_SHORT).show();
                    return;
                }

                long durationMs = Long.parseLong(durationStr) * 60 * 1000;

                List<Utf8String> utf8List = new ArrayList<>();
                for (String s : optsArray) utf8List.add(new Utf8String(s.trim()));
                DynamicArray<Utf8String> web3jOptions = new DynamicArray<>(Utf8String.class, utf8List);

                btnDeploy.setEnabled(false);

                // 有现成 URL（粘贴或默认图）时直接上链，跳过 imgbb 上传
                if (selectedImageUrl != null) {
                    final String finalUrl = selectedImageUrl;
                    btnDeploy.setText("正在上链...");
                    Function f = new Function("createGame", Arrays.asList(
                            new Utf8String(desc), new Utf8String(cond), new Utf8String(finalUrl),
                            new Utf8String(detailInfo), web3jOptions, new Uint256(durationMs)
                    ), Collections.emptyList());
                    executeTx(BigInteger.ZERO, f, "🎉 黄金票据市场发行成功！", btnDeploy);
                    return;
                }

                btnDeploy.setText("正在上传图片到云端...");

                AppExecutors.getInstance().networkIO().execute(() -> {
                    String base64Image = getBase64FromUri(selectedImageUri);
                    if (base64Image == null) {
                        runOnUiThread(() -> {
                            Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show();
                            btnDeploy.setEnabled(true);
                            btnDeploy.setText("签名并上链创建博弈");
                        });
                        return;
                    }

                    try {
                        String imgbbApiKey = "888ee297ffeb9dadd82138c57672647d";
                        URL url = new URL("https://api.imgbb.com/1/upload");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                        String postData = "key=" + imgbbApiKey + "&image=" + URLEncoder.encode(base64Image, "UTF-8");
                        try (java.io.OutputStream os = conn.getOutputStream()) {
                            os.write(postData.getBytes(StandardCharsets.UTF_8));
                        }

                        if (conn.getResponseCode() == 200) {
                            Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                            String response = scanner.hasNext() ? scanner.next() : "";
                            JSONObject json = new JSONObject(response);

                            String uploadedUrl = json.getJSONObject("data").getString("url");

                            AppExecutors.getInstance().mainThread().execute(() -> {
                                btnDeploy.setText("图片上传成功，正在上链...");

                                Function f = new Function("createGame", Arrays.asList(
                                        new Utf8String(desc), new Utf8String(cond), new Utf8String(uploadedUrl),
                                        new Utf8String(detailInfo), web3jOptions, new Uint256(durationMs)
                                ), Collections.emptyList());

                                executeTx(BigInteger.ZERO, f, "🎉 预测池发行成功！", btnDeploy);
                            });
                        } else {
                            runOnUiThread(() -> {
                                Toast.makeText(this, "图片上传被拒 ", Toast.LENGTH_SHORT).show();
                                btnDeploy.setEnabled(true);
                                btnDeploy.setText("签名并上链创建博弈");
                            });
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(this, "上传网络异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            btnDeploy.setEnabled(true);
                            btnDeploy.setText("签名并上链创建博弈");
                        });
                    }
                });
            });
        }

        MaterialButton btnToggleOracle = findViewById(R.id.btn_toggle_oracle);
        if (btnToggleOracle != null) {

            // 🌟 解决滑动冲突：找到黑框 ScrollView，当手指摸到它时，禁止外层页面拦截滑动事件
            android.widget.ScrollView svOracleLog = findViewById(R.id.sv_oracle_log);
            if (svOracleLog != null) {
                svOracleLog.setOnTouchListener((v, event) -> {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return false;
                });
            }

            oracleManager = new OracleDaemonManager(repository, () -> allGamesList, new OracleDaemonManager.OracleCallback() {
                @Override
                public void onLogAppended(String msg) {
                    TextView tvLog = findViewById(R.id.tv_oracle_log);
                    if (tvLog == null) return;
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                    String timeStr = sdf.format(new java.util.Date());
                    String newLog = "[" + timeStr + "] " + msg + "\n" + tvLog.getText().toString();

                    // 🌟 解除封印：把 2000 放宽到 15000 字符，保留详尽的预言机交互和 AI 核查历史
                    if (newLog.length() > 15000) newLog = newLog.substring(0, 15000);
                    tvLog.setText(newLog);
                }

                @Override
                public void onStatusChanged(boolean isRunning) {
                    TextView tvStatus = findViewById(R.id.tv_oracle_status_indicator);
                    if (isRunning) {
                        btnToggleOracle.setText("停止自动化引擎");
                        btnToggleOracle.setBackgroundColor(0xFFEF4444);
                        if (tvStatus != null) {
                            tvStatus.setText("🟢 运行中");
                            tvStatus.setTextColor(0xFF10B981);
                        }
                    } else {
                        btnToggleOracle.setText("启动自动化轮询与抓取");
                        btnToggleOracle.setBackgroundColor(0xFF0052FF);
                        if (tvStatus != null) {
                            tvStatus.setText("🔴 已停止");
                            tvStatus.setTextColor(0xFF94A3B8);
                        }
                    }
                }

                @Override
                public void onResolveSuccess() {
                    syncData();
                }
            });

            btnToggleOracle.setOnClickListener(v -> oracleManager.toggleDaemon());
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

    private void checkOraclePermission() {
        View cvResolve = findViewById(R.id.cv_resolve_market);
        if (cvResolve == null) return;

        cvResolve.setVisibility(View.GONE);

        repository.getOracleAddress(new Web3Repository.DataCallback<String>() {
            @Override
            public void onSuccess(String oracleAddress) {
                String myAddress = repository.getWalletAddress();
                if (myAddress != null && oracleAddress != null
                        && myAddress.equalsIgnoreCase(oracleAddress)) {
                    cvResolve.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onError(String error) {
                cvResolve.setVisibility(View.GONE);
            }
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
            public void onError(String error) {}
        });

        checkOraclePermission();

        binding.llLobbyContainer.removeAllViews();
        TextView loadingView = new TextView(this);
        loadingView.setText("正在同步黄金票据市场数据...");
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
                for (BigInteger s : game.myShares) {
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

        long currentMs = System.currentTimeMillis();
        int renderLimit = 10;
        int renderCount = 0;

        for (Web3Repository.GameModel game : games) {
            if (renderCount >= renderLimit) break;
            renderCount++;

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
            ImageView ivAvatar = itemView.findViewById(R.id.iv_item_avatar);
            LinearLayout llOptions = itemView.findViewById(R.id.ll_options_container);

            tvTitle.setText(game.desc.isEmpty() ? "黄金票据 #" + game.id : game.desc);
            tvVolume.setText("总交易量: " + formatWei(game.totalPool) + " BKC");

            if (ivAvatar != null && game.avatarUrl != null) {
                loadNetworkImage(game.avatarUrl, ivAvatar);
            }

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

            // == 替换 MainActivity.java 里的内层循环 ==
            for (int i = 0; i < game.optionCount; i++) {
                int optionId = i;
                String realOptName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项 " + optionId;

                BigInteger virtualReserve = game.virtualReserves.get(i);
                BigInteger myShares = game.myShares.get(i);

                // 计算胜率，为了让主界面的迷你卡片也显示准确价格
                BigDecimal totalVirtualBd = BigDecimal.ZERO;
                for (int v = 0; v < game.optionCount; v++) {
                    totalVirtualBd = totalVirtualBd.add(new BigDecimal(game.virtualReserves.get(v)));
                }
                float prob = 0f;
                if (totalVirtualBd.compareTo(BigDecimal.ZERO) > 0) {
                    prob = new BigDecimal(virtualReserve).divide(totalVirtualBd, 4, RoundingMode.HALF_UP).floatValue() * 100;
                }
                float priceYes = prob / 100f;
                float priceNo = 1f - priceYes;

                // 挂载新的精美 XML 模板
                View rowView = getLayoutInflater().inflate(R.layout.item_option_row, llOptions, false);

                // 去掉主页面列表过多的内边距，使其更紧凑
                rowView.setPadding(0, 16, 0, 16);

                TextView tvOptName = rowView.findViewById(R.id.tv_row_opt_name);
                TextView tvOptShares = rowView.findViewById(R.id.tv_row_opt_shares);
                TextView tvProb = rowView.findViewById(R.id.tv_row_prob);
                MaterialButton btnBuyYes = rowView.findViewById(R.id.btn_row_buy_yes);
                MaterialButton btnBuyNo = rowView.findViewById(R.id.btn_row_buy_no);

                tvOptName.setText(realOptName);
                tvProb.setText(String.format("%.0f%%", prob));

                if (myShares.signum() > 0) {
                    tvOptShares.setVisibility(View.VISIBLE);
                    tvOptShares.setText("已持有: " + formatWei(myShares) + " 份");
                }

                btnBuyYes.setText(String.format("Yes %.2f", priceYes));
                btnBuyNo.setText(String.format("No %.2f", priceNo));

                if (game.isResolved || game.isRefunded || isExpired) {
                    btnBuyYes.setEnabled(false);
                    btnBuyNo.setEnabled(false);
                    btnBuyYes.setBackgroundColor(0xFFF1F5F9);
                    btnBuyNo.setBackgroundColor(0xFFF1F5F9);
                } else {
                    // 主界面点击统一跳转到详情页即可，不直接在这里做复杂交互，保持层级清晰
                    View.OnClickListener jumpToDetail = v -> {
                        Intent intent = new Intent(MainActivity.this, GameDetailActivity.class);
                        intent.putExtra("PRIVATE_KEY", privateKey);
                        intent.putExtra("GAME_ID", game.id);
                        startActivity(intent);
                    };
                    btnBuyYes.setOnClickListener(jumpToDetail);
                    btnBuyNo.setOnClickListener(jumpToDetail);
                }

                llOptions.addView(rowView);
            }            // AI 分析小卡片：显示在每张游戏卡片底部
            View llActionArea = itemView.findViewById(R.id.ll_action_area);
            com.google.android.material.button.MaterialButton btnAiCard =
                    itemView.findViewById(R.id.btn_ai_analysis_card);
            if (llActionArea != null) llActionArea.setVisibility(View.VISIBLE);
            if (btnAiCard != null) {
                btnAiCard.setOnClickListener(v -> {
                    Intent intent = new Intent(MainActivity.this, AiAnalysisActivity.class);
                    intent.putExtra("PRIVATE_KEY", privateKey);
                    intent.putExtra("GAME_ID", game.id);
                    startActivity(intent);
                });
            }

            binding.llLobbyContainer.addView(itemView);
        }

    }

    private void showStakeDialog(int gameId, int optionId, String optName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认交易策略");
        builder.setMessage("您正在以动态市场价购买：\n[" + optName + "] 的条件代币份额\n\n请输入愿意投入的 BKC 数量:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setPadding(50, 40, 50, 40);
        builder.setView(input);

        builder.setPositiveButton("签名并购买", (dialog, which) -> {
            String amountStr = input.getText().toString();
            if (!amountStr.isEmpty()) {
                try {
                    BigDecimal amount = new BigDecimal(amountStr);
                    BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();
                    // 🌟 核心：这里调用 AMM 机制的新方法名 buyShares
                    Function f = new Function("buyShares", Arrays.asList(new Uint256(gameId), new Uint8(optionId)), Collections.emptyList());
                    executeTx(wei, f, "购买成功，已为您铸造对应份额！", null);
                } catch (Exception e) {
                    Toast.makeText(this, "输入的金额格式不正确", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void executeTx(BigInteger value, Function f, String successMsg, MaterialButton restoreButton) {
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

                    if (restoreButton != null) {
                        restoreButton.setEnabled(true);
                        restoreButton.setText("签名并上链创建博弈");
                        selectedImageUri = null;
                        selectedImageUrl = null;
                        ImageView iv = findViewById(R.id.iv_selected_avatar);
                        if (iv != null) iv.setImageResource(android.R.drawable.ic_menu_camera);
                    }
                }, 1000);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, getFriendlyErrorMessage(error), Toast.LENGTH_LONG).show();
                if (restoreButton != null) {
                    restoreButton.setEnabled(true);
                    restoreButton.setText("签名并上链创建博弈");
                }
            }
        });
    }

    private String formatWei(BigInteger wei) {
        return org.web3j.utils.Convert.fromWei(new BigDecimal(wei), org.web3j.utils.Convert.Unit.ETHER).setScale(1, RoundingMode.HALF_UP).toString();
    }
}