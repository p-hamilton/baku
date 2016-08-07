package examples.baku.io.permissions.synchronization;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

/**
 * Created by phamilton on 8/5/16.
 */
public class SyncTextDiff {

    public final static int DELETE = 0;
    public final static int INSERT = 1;
    public final static int EQUAL = 2;

    public String source;
    public String text;
    public int operation;

    public SyncTextDiff() {
    }

    public SyncTextDiff(String text, int operation, String source) {
        this.text = text;
        this.operation = operation;
        this.source = source;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    public int length() {
        return text == null ? 0 : text.length();
    }

    public boolean compatible(SyncTextDiff other){
        return compatible(other.operation, other.source);
    }

    public boolean compatible(int operation, String source) {
        return operation == this.operation
                && (source == null ? this.source == null : source.equals(this.source));
    }

    public static SyncTextDiff split(SyncTextDiff diff, int start) {
        SyncTextDiff result = new SyncTextDiff();
        result.setSource(diff.source);
        result.setOperation(diff.operation);
        result.setText(diff.text.substring(0, start));
        diff.setText(diff.text.substring(start));
        return result;
    }

    public static SyncTextDiff fromDiff(DiffMatchPatch.Diff diff, String src, String target) {
        SyncTextDiff result = new SyncTextDiff();
        result.setText(diff.text);
        result.setSource(src);
        int op = EQUAL;
        switch (diff.operation) {
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
