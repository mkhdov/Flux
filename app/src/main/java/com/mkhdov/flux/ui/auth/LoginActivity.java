package com.mkhdov.flux.ui.auth;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.mkhdov.flux.R;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.ui.main.MainActivity;

public class LoginActivity extends BaseActivity {
    private AuthManager auth;
    private TextInputEditText emailInput;
    private TextInputEditText passwordInput;
    private TextView errorView;
    private TextView continueLocal;
    private TextView configWarning;
    private MaterialButton signInButton;
    private ProgressBar progress;
    private View emailLayout;
    private View passwordLayout;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = AuthManager.get(this);
        if (auth.hasSession()) {
            openMain();
            return;
        }
        setContentView(R.layout.activity_login);
        emailLayout = findViewById(R.id.login_email_layout);
        passwordLayout = findViewById(R.id.login_password_layout);
        emailInput = findViewById(R.id.login_email);
        passwordInput = findViewById(R.id.login_password);
        errorView = findViewById(R.id.login_error);
        continueLocal = findViewById(R.id.login_continue_local);
        configWarning = findViewById(R.id.login_config_warning);
        signInButton = findViewById(R.id.login_button);
        progress = findViewById(R.id.login_progress);

        signInButton.setOnClickListener(v -> signIn());
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                signIn();
                return true;
            }
            return false;
        });
        continueLocal.setOnClickListener(v -> {
            auth.setLocalOnly(true);
            openMain();
        });

        if (!auth.isConfigured()) {
            emailLayout.setVisibility(View.GONE);
            passwordLayout.setVisibility(View.GONE);
            signInButton.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            configWarning.setVisibility(View.VISIBLE);
        }
    }

    private void signIn() {
        if (!auth.isConfigured()) return;
        String email = emailInput.getText() == null ? "" : emailInput.getText().toString().trim();
        String password = passwordInput.getText() == null ? "" : passwordInput.getText().toString();
        if (email.isEmpty()) {
            errorView.setText(R.string.login_enter_email);
            errorView.setVisibility(View.VISIBLE);
            return;
        }
        if (password.isEmpty()) {
            errorView.setText(R.string.login_enter_password);
            errorView.setVisibility(View.VISIBLE);
            return;
        }
        errorView.setVisibility(View.GONE);
        setBusy(true);
        auth.login(email, password, new AuthManager.Callback() {
            @Override public void onSuccess() {
                setBusy(false);
                CalendarRepository.get(LoginActivity.this).claimLocalDataIfNeeded();
                openMain();
            }
            @Override public void onError(String message) {
                setBusy(false);
                errorView.setText(message);
                errorView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        signInButton.setEnabled(!busy);
        signInButton.setAlpha(busy ? 0.5f : 1f);
        continueLocal.setEnabled(!busy);
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
