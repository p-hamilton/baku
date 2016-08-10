package examples.baku.io.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.support.design.widget.TextInputLayout;
import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.firebase.database.DatabaseError;
import com.onegravity.rteditor.RTEditText;
import com.onegravity.rteditor.api.format.RTHtml;
import com.onegravity.rteditor.api.format.RTText;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import examples.baku.io.permissions.synchronization.SyncText;
import examples.baku.io.permissions.synchronization.SyncTextDiff;

/**
 * Created by phamilton on 7/26/16.
 */
public class PermissionedText extends FrameLayout implements PermissionManager.OnPermissionChangeListener, PermissionManager.OnRequestListener {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private int permissions;
    final Set<PermissionRequest> requests = new HashSet<>();
    private String label;

    private SyncText syncText;
    TextInputLayout textInputLayout;
    EditText editText;

    private OnSuggestionSelectedListener onSuggestionSelectedListener = null;

    public void unlink() {
        if(syncText != null){
            syncText.unlink();
        }
    }

    public interface OnSuggestionSelectedListener {
        void onSelected(SyncTextDiff diff);
    }

    public PermissionedText(Context context) {
        this(context, null, 0);
    }

    public PermissionedText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PermissionedText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    public void init(Context context, AttributeSet attrs, int defStyleAttr) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        textInputLayout = new TextInputLayout(context);
        textInputLayout.setLayoutParams(params);
        String hint = attrs.getAttributeValue(ANDROID_NS, "hint");
        if (hint != null) {
            textInputLayout.setHint(hint);
        }
        editText = new PermissionedEditText(context);
        editText.setId(View.generateViewId());
        editText.setLayoutParams(params);
        int inputType = attrs.getAttributeIntValue(ANDROID_NS, "inputType", EditorInfo.TYPE_NULL);
        if (inputType != EditorInfo.TYPE_NULL) {
            editText.setInputType(inputType);
        }
        textInputLayout.addView(editText, params);
        addView(textInputLayout);
    }

    Spannable diffSpannable(LinkedList<SyncTextDiff> diffs) {
        SpannableStringBuilder result = new SpannableStringBuilder();

        int start;
        String text;
        int color = Color.YELLOW;
        for (SyncTextDiff diff : diffs) {
            start = result.length();
            text = diff.text;
            switch (diff.operation) {
                case SyncTextDiff.DELETE:
                    result.append(text, new BackgroundColorSpan(color), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    result.setSpan(new StrikethroughSpan(), start, start + text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case SyncTextDiff.INSERT:
                    result.append(text, new BackgroundColorSpan(color), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case SyncTextDiff.EQUAL:
                    result.append(text);
                    break;
            }
        }
        return result;
    }

    public void setOnSuggestionSelectedListener(OnSuggestionSelectedListener onSuggestionSelectedListener) {
        this.onSuggestionSelectedListener = onSuggestionSelectedListener;
    }

    public void setSyncText(SyncText sync) {
        this.syncText = sync;

        syncText.setOnTextChangeListener(new SyncText.OnTextChangeListener() {
            @Override
            public void onTextChange(final String currentText, final LinkedList<SyncTextDiff> diffs, int ver) {
                if (ver >= version) {
                    updateText(currentText, diffs);
                }
            }
        });

        editText.addTextChangedListener(watcher);
    }

    public void setPermissions(int permissions) {
        this.permissions = permissions;
        if (syncText != null) {
            this.syncText.setPermissions(permissions);
        }
    }

    @Override
    public void onPermissionChange(int current) {

    }

    private synchronized void updateText(final String currentText, final LinkedList<SyncTextDiff> diffs) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    editText.removeTextChangedListener(watcher);
                    int prevSel = editText.getSelectionStart();
                    editText.setText(diffSpannable(diffs));
                    int sel = Math.min(prevSel, editText.length());
                    if (sel > -1) {
                        editText.setSelection(sel);
                    }
                    editText.addTextChangedListener(watcher);
                }
            });
        }
    }

    private int version = -1;

    private TextWatcher watcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            version = Math.max(version, syncText.update(s.toString()));
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    };

    public void acceptSuggestions(String src) {
        syncText.acceptSuggestions(src);
    }

    public void rejectSuggestions(String src) {
        syncText.rejectSuggestions(src);
    }

    @Override
    public void onCancelled(DatabaseError databaseError) {

    }

    @Override
    public boolean onRequest(PermissionRequest request, Blessing blessing) {
        return false;
    }

    @Override
    public void onRequestRemoved(PermissionRequest request, Blessing blessing) {

    }

    private Activity getActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private void onSelectionChanged(int selStart, int selEnd) {
        if(selStart >= 0){
            SyncTextDiff diff = getDiffAt(selStart);
            if (diff != null && diff.operation != SyncTextDiff.EQUAL) {
                if(onSuggestionSelectedListener != null)
                {
                    onSuggestionSelectedListener.onSelected(diff);
                }
            }
        }
    }

    private SyncTextDiff getDiffAt(int index) {
        int count = 0;
        if (syncText != null) {
            for (SyncTextDiff diff : syncText.getDiffs()) {
                count += diff.length();
                if (index < count) {
                    return diff;
                }
            }
        }
        return null;
    }

    private class PermissionedEditText extends EditText {

        public PermissionedEditText(Context context) {
            super(context);
        }

        public PermissionedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public PermissionedEditText(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        public PermissionedEditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            PermissionedText.this.onSelectionChanged(selStart, selEnd);
        }
    }
}
