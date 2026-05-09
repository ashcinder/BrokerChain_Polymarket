package com.example.testing;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.testing.databinding.ActivityLoginBinding;

/**
 * 【钱包登录/连接界面】
 * 作用：这是整个 DApp 的入口 Activity。
 * 在 Web3 领域，通常不需要传统的“账号密码”注册，而是直接通过加密学“私钥 (Private Key)”来证明身份。
 * 用户在此输入私钥，程序验证格式后将其带入主界面。
 */
public class LoginActivity extends AppCompatActivity {

    // 视图绑定 (ViewBinding) 对象，取代了传统的 findViewById，能有效防止空指针异常
    private ActivityLoginBinding binding;

    // 默认测试私钥（硬编码）
    private static final String DEFAULT_PK = "91d2b0df57b76eee51a7b3f4bda39ec53813968fbf9f8e04033f3c4e9ba4cb2f";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DeepSeekClient.init(this);

        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 监听“连接钱包 (Connect)”按钮的点击事件
        binding.btnLogin.setOnClickListener(v -> {
            // 获取输入框中的私钥，并去除首尾多余的空格
            String inputPk = binding.etPrivateKey.getText().toString().trim();

            // 容错机制：如果用户什么都没输入直接点按钮，就自动使用上面定义的默认测试私钥
            String finalPk = inputPk.isEmpty() ? DEFAULT_PK : inputPk;

            // 私钥格式基础校验：
            // 以太坊/定制链的私钥标准是 64 位十六进制字符，如果带有 "0x" 前缀则是 66 位
            if (finalPk.length() != 64 && !finalPk.startsWith("0x")) {
                Toast.makeText(this, "私钥格式不正确", Toast.LENGTH_SHORT).show();
                return; // 格式错误直接拦截，不再往下执行
            }

            // 构造 Intent，准备跳转到应用主界面 (MainActivity)
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            // 将合法的私钥作为参数 (Extra) 隐式地传递给下一个页面
            intent.putExtra("PRIVATE_KEY", finalPk);
            startActivity(intent);

            // 销毁当前登录页面
            // 作用：防止用户在主界面按手机的“物理返回键”时，又意外退回到这个登录页
            finish();
        });
    }
}