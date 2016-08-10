package examples.baku.io.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.support.design.widget.TextInputLayout;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.google.firebase.database.DatabaseError;
import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.MaterialIcons;

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
    private TextInputLayout textInputLayout;
    private EditText editText;
    private FrameLayout overlay;

    private ImageView actionButton;

    private PermissionedTextListener permissionedTextListener = null;

    public void unlink() {
        if(syncText != null){
            syncText.unlink();
        }
    }

    public interface PermissionedTextListener {
        void onSelected(SyncTextDiff diff, PermissionedText text);
        void onAction(int action, PermissionedText text);
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

    public void init(final Context context, AttributeSet attrs, int defStyleAttr) {
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

        overlay = new FrameLayout(context);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(Color.argb(125, 255,0,0));
        overlay.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });
        overlay.setVisibility(GONE);
        addView(overlay);

        actionButton = new ImageView(context);
        IconDrawable drawable = new IconDrawable(context, MaterialIcons.md_check);
        drawable.color(Color.GREEN);
        actionButton.setImageDrawable(drawable);
        actionButton.setLayoutParams(new FrameLayout.LayoutParams(100,100, Gravity.RIGHT));
        actionButton.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        ImageView view = (ImageView) v;
                        view.getDrawable().setColorFilter(0x77000000, PorterDuff.Mode.SRC_ATOP);
                        view.invalidate();
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        ImageView view = (ImageView) v;
                        view.getDrawable().clearColorFilter();
                        view.invalidate();
                        break;
                    }
                }
                return true;
            }
        });
        addView(actionButton);
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

    public void setPermissionedTextListener(PermissionedTextListener permissionedTextListener) {
        this.permissionedTextListener = permissionedTextListener;
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
                if(permissionedTextListener != null)
                {
                    permissionedTextListener.onSelected(diff, this);
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
