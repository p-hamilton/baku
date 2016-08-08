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
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

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

    private int permissions;
    final Set<PermissionRequest> requests = new HashSet<>();
    private String label;

    private SyncText syncText;
    TextInputLayout textInputLayout;
    EditText editText;

    public PermissionedText(Context context) {
        this(context, null, 0);
    }

    public PermissionedText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PermissionedText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void init(Context context) {


        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        editText = new EditText(context);
        editText.setId(View.generateViewId());
        editText.setLayoutParams(params);
        addView(editText, params);
    }

//    Spannable diffSpannable(LinkedList<DiffMatchPatch.Diff> diffs) {
//        SpannableStringBuilder result = new SpannableStringBuilder();
//
//        int start;
//        String text;
//        int color = Color.YELLOW;
//        for (DiffMatchPatch.Diff diff : diffs) {
//            start = result.length();
//            text = diff.text;
//            switch (diff.operation) {
//                case DELETE:
//                    result.append(text, new BackgroundColorSpan(color), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//                    result.setSpan(new StrikethroughSpan(),start, start + text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//                    break;
//                case INSERT:
//                    result.append(text, new BackgroundColorSpan(color), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//                    break;
//                case EQUAL:
//                    result.append(text);
//                    break;
//            }
//        }
//        return result;
//    }

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
                    result.setSpan(new StrikethroughSpan(),start, start + text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    public void setSyncText(SyncText sync) {
        this.syncText = sync;

        syncText.setOnTextChangeListener(new SyncText.OnTextChangeListener() {
            @Override
            public void onTextChange(final String currentText, final LinkedList<SyncTextDiff> diffs, int ver) {
                Log.e("V", "v"+ver);
                if(ver >= version){
                    updateText(currentText, diffs);
                }
            }
        });

        editText.addTextChangedListener(watcher);
    }

    @Override
    public void onPermissionChange(int current) {

    }

    private synchronized void updateText(final String currentText, final LinkedList<SyncTextDiff> diffs){
        final int prevSel = editText.getSelectionStart();
        Activity activity = getActivity();
        if(activity != null){
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    editText.removeTextChangedListener(watcher);
                    editText.setText(diffSpannable(diffs));
                    int sel = Math.min(prevSel, currentText.length());
                    if (sel > -1) {
                        Log.e("wtf", prevSel + ":"+ sel + " [[ " + currentText.length() + " v" + version);
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
            Log.e("v", "updating v"+version);
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    };

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
                return (Activity)context;
            }
            context = ((ContextWrapper)context).getBaseContext();
        }
        return null;
    }
}
