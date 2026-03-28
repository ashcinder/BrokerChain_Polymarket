package com.example.testing;

import android.graphics.Color;
import android.os.Bundle;
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
import java.util.List;
import java.util.Locale;

/**
 * 【市场详情与交易页面】
 * 作用：展示某个具体预测市场的详细信息（背景图、规则、订单簿），
 * 并利用 MPAndroidChart 绘制基于当前资金池比例的胜率走势图。
 * 同时，用户可在此页面针对特定选项发起“质押 (买入)”交易。
 */
public class GameDetailActivity extends AppCompatActivity {

    private Web3Repository repository;
    private int targetGameId; // 当前页面要展示的博弈池 ID

    // 预设的图表线条颜色集（蓝、黄、红、绿），用于区分不同的下注选项
    private final int[] CHART_COLORS = {0xFF0052FF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_detail);

        // 接收从 MainActivity 列表点击传过来的私钥和博弈池 ID
        String privateKey = getIntent().getStringExtra("PRIVATE_KEY");
        targetGameId = getIntent().getIntExtra("GAME_ID", -1);

        // 如果参数传递失败，直接关闭当前页面，防止程序崩溃
        if (privateKey == null || targetGameId == -1) {
            finish();
            return;
        }

        // 初始化 Web3 仓库用于链上交互
        repository = new Web3Repository(privateKey);

        // 绑定左上角的返回按键
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 开始从区块链异步拉取当前博弈池的最新数据
        loadMarketData();
    }

    /**
     * 从链上获取所有博弈池数据，并过滤出当前页面的目标数据
     */
    private void loadMarketData() {
        repository.getGames(new Web3Repository.DataCallback<List<Web3Repository.GameModel>>() {
            @Override
            public void onSuccess(List<Web3Repository.GameModel> games) {
                // 遍历寻找当前界面的 targetGameId 对应的数据模型
                for (Web3Repository.GameModel game : games) {
                    if (game.id == targetGameId) {
                        renderDetail(game); // 找到后开始渲染 UI
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

    /**
     * 核心渲染方法：将获取到的区块链数据铺设到界面的各个控件上
     */
    private void renderDetail(Web3Repository.GameModel game) {
        TextView tvTitle = findViewById(R.id.tv_detail_title);
        TextView tvVolume = findViewById(R.id.tv_detail_volume);
        TextView tvStatus = findViewById(R.id.tv_detail_status);
        TextView tvCondition = findViewById(R.id.tv_detail_condition);
        TextView tvDeadline = findViewById(R.id.tv_detail_deadline);
        LinearLayout llOptions = findViewById(R.id.ll_detail_options);

        ImageView ivAvatar = findViewById(R.id.iv_detail_avatar);
        TextView tvDetailInfo = findViewById(R.id.tv_detail_info);

        // 1. 填充基础文本信息
        tvTitle.setText(game.desc);
        tvVolume.setText("总交易量: " + formatWei(game.totalPool) + " BKC");
        tvCondition.setText("清算规则: " + game.condition);

        // 2. 填充详细介绍和加载网络头像
        if (tvDetailInfo != null) {
            String infoText = (game.detailedInfo == null || game.detailedInfo.isEmpty()) ? "暂无背景介绍" : game.detailedInfo;
            tvDetailInfo.setText(infoText);
        }
        if (ivAvatar != null && game.avatarUrl != null) {
            // 调用主界面的静态方法加载图片，避免重复造轮子
            MainActivity.loadNetworkImage(game.avatarUrl, ivAvatar);
        }

        // 3. 格式化并显示截止时间（注意这里是毫秒级时间戳）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        tvDeadline.setText("截止时间: " + sdf.format(new Date(game.deadlineSec)));

        // 4. 根据当前时间和链上状态判断博弈池所处的阶段
        long currentMs = System.currentTimeMillis();
        if (game.isResolved) {
            tvStatus.setText("已决议");
            tvStatus.setTextColor(0xFF10B981); // 绿色
        } else if (currentMs > game.deadlineSec || game.isRefunded) {
            tvStatus.setText("已结束");
            tvStatus.setTextColor(0xFF64748B); // 灰色
        } else {
            tvStatus.setText("🟢 交易进行中");
        }

        // 5. 动态渲染“订单簿（可交易选项列表）”
        llOptions.removeAllViews(); // 清空旧数据防止重复叠加
        List<Float> finalProbabilities = new ArrayList<>(); // 用于记录计算出的各选项胜率，传给图表
        BigDecimal totalPoolBd = new BigDecimal(game.totalPool);

        for (int i = 0; i < game.optionCount; i++) {
            final int optionIndex = i; // 选项的底层索引 (0, 1, 2...)
            String optName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项 " + (i + 1);
            final String currentOptName = optName;
            BigInteger poolSize = game.optionPools.get(i);

            // 数学计算：当前选项胜率 = (当前选项吸纳的资金 / 总资金池) * 100
            float prob = 0f;
            if (totalPoolBd.compareTo(BigDecimal.ZERO) > 0) {
                // 使用 BigDecimal 保留高精度除法，RoundingMode.HALF_UP 相当于四舍五入
                prob = new BigDecimal(poolSize).divide(totalPoolBd, 4, RoundingMode.HALF_UP).floatValue() * 100;
            }
            finalProbabilities.add(prob);

            // ============ 开始用 Java 动态创建 UI 布局 ============
            // 创建每一行的外层容器
            LinearLayout optLayout = new LinearLayout(this);
            optLayout.setOrientation(LinearLayout.HORIZONTAL);
            optLayout.setGravity(Gravity.CENTER_VERTICAL);
            optLayout.setPadding(32, 32, 32, 32);
            optLayout.setBackgroundColor(0xFFFFFFFF);

            // 创建选项名称文本 (如 "特朗普胜选")
            TextView tvOptName = new TextView(this);
            tvOptName.setText(optName);
            tvOptName.setTextColor(0xFF0F172A);
            tvOptName.setTextSize(16);
            tvOptName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvOptName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); // 权重为1，占满剩余空间

            // 创建当前胜率文本 (如 "65.5%")
            TextView tvProb = new TextView(this);
            tvProb.setText(String.format("%.1f%%", prob));
            tvProb.setTextColor(CHART_COLORS[i % CHART_COLORS.length]); // 根据索引取对应的颜色
            tvProb.setTextSize(16);
            tvProb.setTypeface(null, android.graphics.Typeface.BOLD);
            tvProb.setPadding(0, 0, 32, 0);

            // 创建交易动作按钮
            MaterialButton btnBuy = new MaterialButton(this);
            btnBuy.setText("买入");
            btnBuy.setBackgroundColor(0xFFEFF6FF);
            btnBuy.setTextColor(0xFF0052FF);
            btnBuy.setCornerRadius(12);

            // 状态控制：如果博弈结束，则按钮置灰并禁用点击
            if (game.isResolved || game.isRefunded || currentMs > game.deadlineSec) {
                btnBuy.setEnabled(false);
                btnBuy.setBackgroundColor(0xFFF1F5F9);
                btnBuy.setTextColor(0xFF94A3B8);
            } else {
                // 绑定点击事件，呼出交易确认弹窗
                btnBuy.setOnClickListener(v -> showStakeDialog(game.id, optionIndex, currentOptName));
            }

            // 将三个控件依次装入横向容器
            optLayout.addView(tvOptName);
            optLayout.addView(tvProb);
            optLayout.addView(btnBuy);
            // 将当前行装入最外层的大容器
            llOptions.addView(optLayout);

            // 每行底部增加一条灰色分割线 (最后一行不加)
            if (i < game.optionCount - 1) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));
                divider.setBackgroundColor(0xFFF1F5F9);
                llOptions.addView(divider);
            }
        }

        // 6. 根据刚才算出来的最终胜率，渲染折线图表
        setupChart(game, finalProbabilities);

        // 🌟 新增：绑定 AI 分析按钮点击事件
        MaterialButton btnAi = findViewById(R.id.btn_ai_analysis);
        if (btnAi != null) {
            btnAi.setOnClickListener(v -> {
                // 1. 抓取该博弈池的所有数据，拼接成一个完美的提示词 (Prompt) 喂给 DeepSeek
                StringBuilder prompt = new StringBuilder();
                prompt.append("请帮我分析一下这个基于区块链的预测市场博弈项目，并给出投资建议：\n\n");
                prompt.append("【博弈主题】: ").append(game.desc).append("\n");
                prompt.append("【清算规则】: ").append(game.condition).append("\n");
                prompt.append("【详细背景】: ").append((game.detailedInfo == null || game.detailedInfo.isEmpty()) ? "无" : game.detailedInfo).append("\n");
                prompt.append("【当前总资金池】: ").append(formatWei(game.totalPool)).append(" BKC\n\n");

                prompt.append("目前各选项的资金盘口分布如下：\n");
                for (int i = 0; i < game.optionCount; i++) {
                    String optName = (i < game.optionNames.size()) ? game.optionNames.get(i) : "选项" + (i+1);
                    prompt.append("- ").append(optName)
                            .append("：当前已买入 ").append(formatWei(game.optionPools.get(i)))
                            // 🚨 修正点：这里使用本方法内已经计算好的 finalProbabilities 列表
                            .append(" BKC，当前胜率约 ").append(String.format("%.1f%%", finalProbabilities.get(i))).append("\n");
                }

                prompt.append("\n基于上述数据、背景和当前盘口资金分布，请判断这个市场的走向，哪一个选项胜出的概率大？风险在哪里？");

                // 2. 跳转到新建立的 AiAnalysisActivity，并把拼接好的提示词传过去
                android.content.Intent intent = new android.content.Intent(GameDetailActivity.this, AiAnalysisActivity.class);
                intent.putExtra("INITIAL_PROMPT", prompt.toString());
                startActivity(intent);
            });
        }
    }

    /**
     * 图表渲染器 (基于 MPAndroidChart 库)
     * 作用：为了视觉美观，模拟生成一组走势数据并渲染成折线图。
     */
    private void setupChart(Web3Repository.GameModel game, List<Float> finalProbabilities) {
        LineChart chart = findViewById(R.id.chart_market_trend);
        chart.getDescription().setEnabled(false); // 禁用默认的图表右下角描述说明
        chart.getLegend().setEnabled(true);       // 显示图例（哪种颜色代表哪个选项）
        chart.setDrawGridBackground(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(false);

        // 配置 X 轴（时间跨度：假定为 0-10 天）
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); // X 坐标放在底部
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(0xFF94A3B8);

        // 配置左侧 Y 轴（概率：固定 0 到 100 之间）
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);
        leftAxis.setTextColor(0xFF94A3B8);
        leftAxis.setGridColor(0xFFF1F5F9);

        // 禁用右侧的 Y 轴刻度，保持界面干净
        chart.getAxisRight().setEnabled(false);

        // 存放所有线段数据的集合
        LineData lineData = new LineData();

        // 针对每一个可下注选项，生成一条对应的折线
        for (int i = 0; i < finalProbabilities.size(); i++) {
            List<Entry> entries = new ArrayList<>();
            float finalProb = finalProbabilities.get(i); // 实际计算出的最终链上概率
            float currentSimulatedProb = 100f / finalProbabilities.size(); // 假定图表的起点是大家平分秋色

            // 生成前 10 天的模拟波动数据
            for (int day = 0; day < 10; day++) {
                entries.add(new Entry(day, currentSimulatedProb));
                // 模拟算法：让数值逐渐向最终真实结果 (finalProb) 靠拢，并加入一点随机波动
                currentSimulatedProb += (finalProb - currentSimulatedProb) / (10 - day) + (float) (Math.random() * 5 - 2.5);
                // 截断越界数据，保证概率在 0~100 之间
                if (currentSimulatedProb < 0) currentSimulatedProb = 0;
                if (currentSimulatedProb > 100) currentSimulatedProb = 100;
            }
            // 将最后一天的点严格锚定为链上的真实计算结果
            entries.add(new Entry(10, finalProb));

            // 配置这条线的样式
            String label = (i < game.optionNames.size()) ? game.optionNames.get(i) : "Option " + (i + 1);
            LineDataSet dataSet = new LineDataSet(entries, label);
            int color = CHART_COLORS[i % CHART_COLORS.length];
            dataSet.setColor(color);          // 线条颜色
            dataSet.setCircleColor(color);    // 数据点圆圈颜色
            dataSet.setLineWidth(2.5f);       // 线条粗细
            dataSet.setCircleRadius(4f);      // 圆圈大小
            dataSet.setDrawValues(false);     // 不在图表上直接显示数字，避免拥挤

            lineData.addDataSet(dataSet);
        }

        // 把组装好的数据塞进图表，并开启动画（延展 1 秒钟）
        chart.setData(lineData);
        chart.animateX(1000);
    }

    /**
     * 辅助工具：将以太坊最小单位 (Wei) 转换为方便人类阅读的 (Ether/BKC)，保留一位小数
     */
    private String formatWei(BigInteger wei) {
        return org.web3j.utils.Convert.fromWei(new BigDecimal(wei), org.web3j.utils.Convert.Unit.ETHER)
                .setScale(1, RoundingMode.HALF_UP).toString();
    }

    /**
     * 弹出二次确认框，收集用户要购买的金额并向链上广播
     */
    private void showStakeDialog(int gameId, int optionId, String optName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认交易策略");
        builder.setMessage("您正在质押：\n[" + optName + "]\n\n请输入投入的 BKC 数量:");

        // 创建一个数字输入框，允许带小数点
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setPadding(50, 40, 50, 40);
        builder.setView(input);

        // 用户点击“签名并广播”
        builder.setPositiveButton("签名并广播", (dialog, which) -> {
            String amountStr = input.getText().toString();
            if (!amountStr.isEmpty()) {
                try {
                    // 1. 将输入的数量转换回以太坊底层的 Wei 单位 (乘以 10 的 18 次方)
                    BigDecimal amount = new BigDecimal(amountStr);
                    BigInteger wei = org.web3j.utils.Convert.toWei(amount, org.web3j.utils.Convert.Unit.ETHER).toBigInteger();

                    // 2. 组装发给智能合约的方法名 stakeTokens 以及参数 [gameId, optionId]
                    Function f = new Function("stakeTokens", Arrays.asList(new Uint256(gameId), new Uint8(optionId)), Collections.emptyList());

                    // 3. 执行交易发送逻辑
                    executeTx(wei, f, "质押成功，已上链确认！");
                } catch (Exception e) {
                    Toast.makeText(this, "输入的金额格式不正确", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /**
     * 封装交易发送的过程及状态回调处理
     */
    private void executeTx(BigInteger value, Function f, String successMsg) {
        Toast.makeText(this, "⏳ 正在广播交易...", Toast.LENGTH_SHORT).show();

        repository.sendTransaction(value, f, successMsg, new Web3Repository.TxCallback() {
            @Override
            public void onTxSent(String txHash) {
            }

            @Override
            public void onConfirmed(String message) {
                // 交易成功被节点确认后执行
                Toast.makeText(GameDetailActivity.this, message + " (正在等待区块确认...)", Toast.LENGTH_LONG).show();
                // 延时 1 秒重新加载本页数据（因为区块链打包存在微小延迟，防止刷新太快数据还没变）
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> loadMarketData(), 1000);
            }

            @Override
            public void onError(String error) {
                // 交易失败或发生 revert
                Toast.makeText(GameDetailActivity.this, error != null ? error : "交易失败", Toast.LENGTH_LONG).show();
            }
        });
    }
}