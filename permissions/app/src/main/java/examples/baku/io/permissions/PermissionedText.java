package examples.baku.io.permissions;

import android.content.Context;
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

/**
 * Created by phamilton on 7/26/16.
 */
public class PermissionedText extends FrameLayout implements PermissionManager.OnPermissionChangeListener, PermissionManager.OnRequestListener {

    private int permissions;
    final Set<PermissionRequest> requests = new HashSet<>();
    private String label;

    private SyncText syncText;
    TextInputLayout textInputLayout;
    RTEditText editText;

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


        final EditText editText;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        editText = new EditText(context);
        editText.setId(View.generateViewId());
        editText.setLayoutParams(params);
//        RTText x = new RTHtml("<b>lalala</b>");
        DiffMatchPatch d = new DiffMatchPatch();
        LinkedList<DiffMatchPatch.Diff> ds = d.diffMain("wasappppp", "wazzzzszzzzz");
        editText.setText(diffSpannable(ds));
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
        addView(editText, params);
    }

    Spannable diffSpannable(LinkedList<DiffMatchPatch.Diff> diffs) {
        SpannableStringBuilder result = new SpannableStringBuilder();

        int start;
        String text;
        int color = Color.YELLOW;
        for (DiffMatchPatch.Diff diff : diffs) {
            start = result.length();
            text = diff.text;
            switch (diff.operation) {
                case DELETE:
                    result.append(text, new BackgroundColorSpan(color), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    result.setSpan(new StrikethroughSpan(),start, start + text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case INSERT:
                    result.append(text, new BackgroundColorSpan(color), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case EQUAL:
                    result.append(text);
                    break;
            }
        }
        return result;
    }

    @Override
    public void onPermissionChange(int current) {

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
}
