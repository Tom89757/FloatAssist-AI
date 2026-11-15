package com.dshgojava.assist.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.dshgojava.assist.R;
import com.dshgojava.assist.model.DirectApiModelChannel;
import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.role.Role;
import com.dshgojava.assist.role.RoleGenerator;
import com.dshgojava.assist.role.RoleStore;
import com.dshgojava.assist.service.AssistOverlayService;
import com.dshgojava.assist.store.AssistPrefs;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Creates a new role: enter an identity, auto-generate the other fields, edit and save. */
public final class RoleEditActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private EditText identity;
    private EditText expertise;
    private EditText tone;
    private EditText output;
    private EditText guardrails;
    private Button generate;
    private Button save;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, pad, pad, pad);
        scroller.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.assist_role_edit_title);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(title, wrap());

        identity = addField(panel, R.string.assist_role_identity,
                R.string.assist_role_identity_hint, false);

        generate = new Button(this);
        generate.setText(R.string.assist_role_generate);
        generate.setOnClickListener(view -> generateFields());
        panel.addView(generate, wrap());

        expertise = addField(panel, R.string.assist_role_expertise, 0, true);
        tone = addField(panel, R.string.assist_role_tone, 0, true);
        output = addField(panel, R.string.assist_role_output, 0, true);
        guardrails = addField(panel, R.string.assist_role_guardrails, 0, true);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        Button cancel = new Button(this);
        cancel.setText(R.string.assist_role_cancel);
        cancel.setOnClickListener(view -> finish());
        save = new Button(this);
        save.setText(R.string.assist_role_save);
        save.setOnClickListener(view -> save());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.rightMargin = dp(8);
        actions.addView(cancel, cancelParams);
        actions.addView(save, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(actions, wrap());

        return scroller;
    }

    private EditText addField(LinearLayout panel, int labelRes, int hintRes, boolean multiline) {
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(13);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(12);
        panel.addView(label, labelParams);

        EditText field = new EditText(this);
        if (hintRes != 0) field.setHint(hintRes);
        if (multiline) {
            field.setMinLines(2);
            field.setGravity(Gravity.TOP);
        } else {
            field.setSingleLine(true);
        }
        panel.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private LinearLayout.LayoutParams wrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
    }

    private void generateFields() {
        String value = identity.getText().toString().strip();
        if (value.isEmpty()) {
            toast(R.string.assist_role_empty_identity);
            return;
        }
        setBusy(true);
        generate.setText(R.string.assist_role_generating);
        io.execute(() -> {
            ModelChannel model = buildModel();
            if (model == null) {
                main.post(() -> {
                    setBusy(false);
                    toast(R.string.assist_role_model_unconfigured);
                });
                return;
            }
            RoleGenerator.generate(model, value).whenComplete((draft, error) ->
                    main.post(() -> {
                        setBusy(false);
                        if (error != null) {
                            toast(R.string.assist_role_generate_failed);
                            return;
                        }
                        expertise.setText(draft.expertise());
                        tone.setText(draft.tone());
                        output.setText(draft.output());
                        guardrails.setText(draft.guardrails());
                    }));
        });
    }

    private void save() {
        String value = identity.getText().toString().strip();
        if (value.isEmpty()) {
            toast(R.string.assist_role_empty_identity);
            return;
        }
        Role role = new Role(
                RoleStore.newCustomId(),
                value,
                value,
                expertise.getText().toString(),
                tone.getText().toString(),
                output.getText().toString(),
                guardrails.getText().toString(),
                false);
        RoleStore store = new RoleStore(this);
        store.addCustom(role);
        store.select(role.id());
        AssistOverlayService.reload(this);
        Toast.makeText(this, getString(R.string.assist_role_selected, role.name()),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private ModelChannel buildModel() {
        AssistPrefs.Snapshot snapshot = new AssistPrefs(this).read();
        return snapshot.model().configured()
                ? new DirectApiModelChannel(snapshot.model()) : null;
    }

    private void setBusy(boolean busy) {
        generate.setEnabled(!busy);
        save.setEnabled(!busy);
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
