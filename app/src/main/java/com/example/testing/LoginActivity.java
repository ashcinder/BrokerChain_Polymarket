package com.example.testing;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.testing.databinding.ActivityLoginBinding;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private static final String DEFAULT_PK = "ad5799695148adb16b3a31ef150ccaea7f9b4ed8308dceaa66e2e9a6e4133dbb";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnLogin.setOnClickListener(v -> {
            String inputPk = binding.etPrivateKey.getText().toString().trim();
            String finalPk = inputPk.isEmpty() ? DEFAULT_PK : inputPk;

            if (finalPk.length() != 64 && !finalPk.startsWith("0x")) {
                Toast.makeText(this, "私钥格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            intent.putExtra("PRIVATE_KEY", finalPk);
            startActivity(intent);
            finish();
        });
    }
}