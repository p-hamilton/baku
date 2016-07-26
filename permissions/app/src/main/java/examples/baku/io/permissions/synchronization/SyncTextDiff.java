package examples.baku.io.permissions.synchronization;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

/**
 * Created by phamilton on 8/5/16.
 */
public class SyncTextDiff {

    public final static int EQUAL = 0;
    public final static int DELETE = 1;
    public final static int INSERT = 2;

    private String source;
    private String target;
    private String text;
    private int operation;

    public SyncTextDiff() {
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getOperation() {
        return operation;
    }

    public void setOperation(int operation) {
        this.operation = operation;
    }

    public int length(){
        return text == null ? 0 : text.length();
    }

    public static SyncTextDiff split(SyncTextDiff diff, int start){
        SyncTextDiff result = new SyncTextDiff();
        result.setSource(diff.source);
        result.setOperation(diff.operation);
        result.setTarget(diff.target);
        result.setText(diff.text.substring(0, start));
        diff.setText(diff.text.substring(start));
        return result;
    }

    public static SyncTextDiff fromDiff(DiffMatchPatch.Diff diff, String src, String target){
        SyncTextDiff result = new SyncTextDiff();
        result.setText(diff.text);
        result.setSource(src);
        int op = EQUAL;
        switch (diff.operation){
            case DELETE:
                op = SyncTextDiff.DELETE;
                break;
            case INSERT:
                op = SyncTextDiff.INSERT;
        }
        result.setOperation(op);
        return result;
    }


}
