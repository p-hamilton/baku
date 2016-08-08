// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions.synchronization;

import android.util.Log;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.GenericTypeIndicator;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by phamilton on 6/24/16.
 */
public class SyncText {

    static final String KEY_CURRENT = "current";
    static final String KEY_TEXT = "value";
    static final String KEY_VERSION = "version";
    static final String KEY_PATCHES = "patches";
    static final String KEY_SUBSCRIBERS = "subscribers";
    static final String KEY_DIFFS = "diffs";

    private final GenericTypeIndicator<ArrayList<SyncTextDiff>> diffListType = new GenericTypeIndicator<ArrayList<SyncTextDiff>>() {
    };

    private LinkedList<SyncTextDiff> diffs = new LinkedList<>();
    private int ver;
    private BlockingQueue<SyncTextPatch> mPatchQueue;

    private DiffMatchPatch diffMatchPatch = new DiffMatchPatch();

    private DatabaseReference mSyncRef;
    private DatabaseReference mPatchesRef;
    private DatabaseReference mOutputRef;

    private PatchConsumer mPatchConsumer;

    private OnTextChangeListener mOnTextChangeListener;

    private String mInstanceId;
    private String mLocalSource;


    public SyncText(String owner, DatabaseReference reference, DatabaseReference output) {
        if (reference == null) throw new IllegalArgumentException("null reference");

        mLocalSource = owner;
        mSyncRef = reference;
        mOutputRef = output;

        mInstanceId = UUID.randomUUID().toString();

        mPatchQueue = new LinkedBlockingQueue<>();
        mPatchConsumer = new PatchConsumer(mPatchQueue);

        new Thread(mPatchConsumer).start();

        link();
    }

    public String getLocalSource() {
        return mLocalSource;
    }

    public void setLocalSource(String localSource) {
        this.mLocalSource = localSource;
    }

//    public String getText() {
//        return text;
//    }
//
//    public void setText(String text) {
//        this.text = text;
//    }

    public String getFinalText() {
        String result = "";
        for (SyncTextDiff diff : this.diffs) {
            if (diff.operation == SyncTextDiff.EQUAL) {
                result += diff.getText();
            }
        }
        return result;
    }

    public int getVer() {
        return ver;
    }

    public void setVer(int ver) {
        this.ver = ver;
    }

    public void setOnTextChangeListener(OnTextChangeListener onTextChangeListener) {
        this.mOnTextChangeListener = onTextChangeListener;
    }

    public int update(String newText) {
        if (mPatchesRef == null) {
            throw new RuntimeException("database connection hasn't been initialized");
        }

        LinkedList<DiffMatchPatch.Patch> patches = diffMatchPatch.patchMake(getFinalText(), newText);

        if (patches.size() > 0) {
            String patchString = diffMatchPatch.patchToText(patches);
            SyncTextPatch patch = new SyncTextPatch();
            patch.setVer(ver + 1);
            patch.setPatch(patchString);
            if (mLocalSource != null) {
                patch.setSource(mLocalSource);
            }
            mPatchesRef.push().setValue(patch);
            return ver + 1;
        }
        return -1;
    }

//    private void processPatch(SyncTextPatch patch) {
//
//
//
//        if(updateCurrent(patch)){
//
//        }
//
//        LinkedList<DiffMatchPatch.Patch> remotePatch = new LinkedList<>(diffMatchPatch.patchFromText(patch.getPatch()));
//        Object[] results = diffMatchPatch.patchApply(remotePatch, this.text);
//        //TODO: check results
//        if (results != null && results.length > 0 && results[0] instanceof String) {
//            String patchedString = (String) results[0];
//            this.ver = v;
//            this.text = patchedString;
//            updateCurrent();
//        }
//    }

    private void updateCurrent() {
        final String text = getFinalText();
        mSyncRef.child(KEY_CURRENT).runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    currentData.child(KEY_TEXT).setValue(text);
                    currentData.child(KEY_VERSION).setValue(ver);
                    currentData.child(KEY_DIFFS).setValue(diffs);

                } else {
                    int latest = currentData.child(KEY_VERSION).getValue(Integer.class);
                    if (latest > ver) {
                        return Transaction.abort();
                    }
                    currentData.child(KEY_TEXT).setValue(text);
                    currentData.child(KEY_VERSION).setValue(ver);
                    currentData.child(KEY_DIFFS).setValue(diffs);
                }
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError databaseError, boolean success, DataSnapshot dataSnapshot) {
                if (success) {
                    if (mOnTextChangeListener != null) {
                        mOnTextChangeListener.onTextChange(text, diffs, ver);
                    }
                    if (mOutputRef != null) {  //pass successful change to output location
                        mOutputRef.setValue(text);
                    }
                }
            }
        });
    }

    public void link() {

        mSyncRef.child(KEY_SUBSCRIBERS).child(mInstanceId).setValue(0);

        mPatchesRef = mSyncRef.child(KEY_PATCHES);
        mSyncRef.child(KEY_CURRENT).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    if (dataSnapshot.hasChild(KEY_DIFFS)) {
                        diffs = new LinkedList<SyncTextDiff>(dataSnapshot.child(KEY_DIFFS).getValue(diffListType));
                    }
                    ver = dataSnapshot.child(KEY_VERSION).getValue(Integer.class);
                } else {  //version 0, empty string
                    updateCurrent();
                }

//                mPatchesRef.orderByChild(KEY_VERSION).startAt(ver).addChildEventListener(mPatchListener);
                mPatchesRef.addChildEventListener(mPatchListener);

                if (mOnTextChangeListener != null) {
                    String text = getFinalText();
                    mOnTextChangeListener.onTextChange(text, diffs, ver);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private ChildEventListener mPatchListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            if (dataSnapshot.exists()) {
                try {
                    SyncTextPatch patch = dataSnapshot.getValue(SyncTextPatch.class);
                    if (patch != null) {
                        mPatchQueue.add(patch);
                    }
                } catch (DatabaseException e) {
                    e.printStackTrace();
                }
            }

        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {

        }

        @Override
        public void onChildMoved(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };

    public void unlink() {
        mSyncRef.child(KEY_PATCHES).removeEventListener(mPatchListener);
        mSyncRef.child(KEY_SUBSCRIBERS).child(mInstanceId).removeValue();
    }

    public interface OnTextChangeListener {
        void onTextChange(String finalText, LinkedList<SyncTextDiff> diffs, int ver);
    }

    private class PatchConsumer implements Runnable {
        private final BlockingQueue<SyncTextPatch> queue;

        PatchConsumer(BlockingQueue q) {
            queue = q;
        }

        public void run() {
            try {
                while (true) {
                    consume(queue.take());
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        void consume(SyncTextPatch patch) {
            processPatch(patch);
        }
    }

    private static String fromDiffs(List<SyncTextDiff> diffs) {
        String result = "";
        for (SyncTextDiff diff : diffs) {
            result += diff.getText();
        }
        return result;
    }

    boolean hasWrite(String source) {
        return source != null && source.equals(mLocalSource);
    }

    //TODO: this method doesn't handle delete operations on diffs with different sources (e.g. deleting another sources suggestion), these operations are currently ignored
    void processPatch(SyncTextPatch patch) {
        int v = patch.getVer();
        if (this.ver >= v) {  //ignore patches for previous versions
            return;
        }

        String previous = fromDiffs(this.diffs);
        String source = patch.getSource();
        LinkedList<DiffMatchPatch.Patch> patches = new LinkedList<>(diffMatchPatch.patchFromText(patch.getPatch()));
        Object[] patchResults = diffMatchPatch.patchApply(patches, previous);

        if (patchResults == null) {   //return if failed to apply patch
            return;
        }

        this.ver = v;
        String patched = (String) patchResults[0];
        LinkedList<DiffMatchPatch.Diff> diffs = diffMatchPatch.diffMain(previous, patched);
        LinkedList<SyncTextDiff> result = new LinkedList<>(this.diffs);
        ListIterator<SyncTextDiff> resultIterator = result.listIterator();
        SyncTextDiff previousDiff = null;

        for (DiffMatchPatch.Diff current : diffs) {
            int operation = current.operation.ordinal();
            String value = current.text;
            Log.e("zz", "Value: " + value + " op: " + operation);

            if (current.operation == DiffMatchPatch.Operation.INSERT) {
                if (hasWrite(source)) {
                    resultIterator.add(new SyncTextDiff(current.text, SyncTextDiff.EQUAL, source));
                } else {
                    resultIterator.add(new SyncTextDiff(current.text, operation, source));
                }
                resultIterator.previous();
                Log.e("zz", "ADDING3 " + current.text);
                if (resultIterator.hasNext()) {
                    Log.e("zz", "NEXT3");
                    previousDiff = resultIterator.next();
                }
            } else {
                int length = value.length();
                if (previousDiff == null) {
                    previousDiff = resultIterator.next();
                    Log.e("zz", "NEXT");
                }
                while (previousDiff.length() < length) {
                    if (current.operation == DiffMatchPatch.Operation.DELETE) {
                        if (hasWrite(source)) {
                            Log.e("zz", "REMOVING " + previousDiff.text);
                            resultIterator.remove();
                        } else {
                            Log.e("zz", "REMOVING3");
                            previousDiff.setOperation(operation);
                            previousDiff.setSource(source);
                        }
                    }
                    length -= previousDiff.length();
                    if (resultIterator.hasNext()) {
                        Log.e("zz", "NEXT2");
                        previousDiff = resultIterator.next();
                    } else {
                        Log.e("zz", "LENGTH " + length);
                        break;
                    }
                }

                if (length != 0 && length != previousDiff.length()) {
                    Log.e("zz", "Splitting " + previousDiff.text + " at " + length);
                    SyncTextDiff splitDiff = SyncTextDiff.split(previousDiff, length);
                    Log.e("zz", splitDiff.text + " - " + previousDiff.text);
                    if (current.operation == DiffMatchPatch.Operation.DELETE) {
                        if (!hasWrite(source) && !source.equals(splitDiff.source)) {
                            splitDiff.setSource(source);
                            splitDiff.setOperation(operation);
                            Log.e("zz", "ADDING " + splitDiff.text);
                            resultIterator.add(splitDiff);
                            previousDiff = resultIterator.previous();
                        }
                    } else {   //EQUAL, unchanged
                        Log.e("zz", "ADDING2 " + splitDiff.text);
                        resultIterator.add(splitDiff);
                        previousDiff = resultIterator.previous();
                    }
                } else {
                    if (resultIterator.hasNext()) {
                        previousDiff = resultIterator.next();
                        Log.e("zz", "NEXt4 " +previousDiff.text);
                    }
                    if (current.operation == DiffMatchPatch.Operation.DELETE && hasWrite(source)) {
                        Log.e("zz", "REMOVING2 " +previousDiff.text);
                        resultIterator.remove();
                    }
                }
            }
        }

        //merge compatible diffs
        if (!result.isEmpty()) {
            Iterator<SyncTextDiff> iterator = result.iterator();
            SyncTextDiff neighbor = iterator.next();
            String wtf = neighbor.text;
            while (iterator.hasNext()) {
                SyncTextDiff diff = iterator.next();
                wtf += " - " + diff.text;
                if (neighbor.compatible(diff)) {
                    neighbor.text += diff.text;
                    iterator.remove();
                } else {
                    neighbor = diff;
                }
            }
            Log.e("wtf", wtf);
        }

        this.diffs = result;
        updateCurrent();
    }


    void acceptSuggestions(String source) {

    }

    void rejectSuggestions(String source) {

    }

}
