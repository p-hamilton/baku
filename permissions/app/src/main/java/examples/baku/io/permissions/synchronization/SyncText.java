// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions.synchronization;

import android.text.Spannable;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

import java.util.Iterator;
import java.util.LinkedList;
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
    static final String KEY_SUGGESTIONS = "suggestions";

    private String text = "";
    private LinkedList<SyncTextDiff> diffs;
    private int ver;
    private BlockingQueue<SyncTextPatch> mPatchQueue;

    private DiffMatchPatch diffMatchPatch = new DiffMatchPatch();

    private DatabaseReference mSyncRef;
    private DatabaseReference mPatchesRef;
    private DatabaseReference mOutputRef;

    private PatchConsumer mPatchConsumer;

    private OnTextChangeListener mOnTextChangeListener;

    private String mId;

    private String mInstance;

    private String mLocalSource;


    public SyncText(DatabaseReference reference, DatabaseReference output){
        if(reference == null) throw new IllegalArgumentException("null reference");

        mInstance = UUID.randomUUID().toString();

        mSyncRef = reference;
        mOutputRef = output;

        mId = UUID.randomUUID().toString();

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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
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

    public void update(String newText){
        if(mPatchesRef == null){
            throw new RuntimeException("database connection hasn't been initialized");
        }

        LinkedList<DiffMatchPatch.Patch> patches = diffMatchPatch.patchMake(text, newText);

        if(patches.size() > 0){
            String patchString = diffMatchPatch.patchToText(patches);
            SyncTextPatch patch = new SyncTextPatch();
            patch.setVer(ver + 1);
            patch.setPatch(patchString);
            if(mLocalSource != null){
                patch.setSource(mLocalSource);
            }
            mPatchesRef.push().setValue(patch);
        }
    }

    private void processPatch(SyncTextPatch patch){

        int v = patch.getVer();
        if(this.ver >= v){  //ignore patches for previous versions
            return;
        }

        LinkedList<DiffMatchPatch.Patch> remotePatch = new LinkedList<>(diffMatchPatch.patchFromText(patch.getPatch()));
        Object[] results = diffMatchPatch.patchApply(remotePatch, this.text);
        //TODO: check results
        if(results != null && results.length > 0 && results[0] instanceof String){
            String patchedString = (String)results[0];
            this.ver = v;
            this.text = patchedString;
            updateCurrent();
        }
    }

    private void updateCurrent(){
        mSyncRef.child(KEY_CURRENT).runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if(currentData.getValue() == null){
                    currentData.child(KEY_TEXT).setValue(text);
                    currentData.child(KEY_VERSION).setValue(ver);
                }else{
                    int latest = currentData.child(KEY_VERSION).getValue(Integer.class);
                    if(latest > ver){
                        return Transaction.abort();
                    }
                    currentData.child(KEY_TEXT).setValue(text);
                    currentData.child(KEY_VERSION).setValue(ver);
                }
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError databaseError, boolean success, DataSnapshot dataSnapshot) {
                if(success){
                    if(mOnTextChangeListener != null){
                        mOnTextChangeListener.onTextChange(text);
                    }
                    if(mOutputRef != null){  //pass successful change to output location
                        mOutputRef.setValue(text);
                    }
                }
            }
        });
    }

    public void link(){

        mSyncRef.child(KEY_SUBSCRIBERS).child(mId).setValue(0);

        mPatchesRef = mSyncRef.child(KEY_PATCHES);
        mSyncRef.child(KEY_CURRENT).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(dataSnapshot.exists()){
                    text = dataSnapshot.child(KEY_TEXT).getValue(String.class);
                    ver = dataSnapshot.child(KEY_VERSION).getValue(Integer.class);
                }else if(mOutputRef != null){   //check if output ref already has a value
                    mOutputRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot dataSnapshot) {
                            if(dataSnapshot.exists() && dataSnapshot.getValue() != null){
                                text = dataSnapshot.getValue(String.class);
                                original = text;
                            }
                            updateCurrent();
                        }
                        @Override
                        public void onCancelled(DatabaseError databaseError) {

                        }
                    });
                }else{  //version 0, empty string
                    updateCurrent();
                }

//                mPatchesRef.orderByChild(KEY_VERSION).startAt(ver).addChildEventListener(mPatchListener);
                mPatchesRef.addChildEventListener(mPatchListener);

                if(mOnTextChangeListener != null){
                    mOnTextChangeListener.onTextChange(text);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    public String getOriginal() {
        return original;
    }

    private ChildEventListener mPatchListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            if(dataSnapshot.exists()){
                try{
                    SyncTextPatch patch = dataSnapshot.getValue(SyncTextPatch.class);
                    if(patch != null){
                        mPatchQueue.add(patch);
                    }
                }catch(DatabaseException e){
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

    public void unlink(){
        mSyncRef.child(KEY_PATCHES).removeEventListener(mPatchListener);
        mSyncRef.child(KEY_SUBSCRIBERS).child(mId).removeValue();
    }

    public interface OnTextChangeListener{
        void onTextChange(String currentText, String source);
    }

    private class PatchConsumer implements Runnable {
        private final BlockingQueue<SyncTextPatch> queue;

        PatchConsumer(BlockingQueue q) { queue = q; }
        public void run() {
            try {
                while (true) { consume(queue.take()); }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        void consume(SyncTextPatch patch) {
            processPatch(patch);
        }
    }

    String fromDiffs(SyncTextDiff diffs){
        String result = "";
        for(SyncTextDiff diff : diffs){
            result
        }
        return result;
    }

    LinkedList<SyncTextDiff> updateCurrent(SyncTextPatch patch){
        LinkedList<SyncTextDiff> result = new LinkedList<>(diffs);
        ListIterator<SyncTextDiff> current = result.listIterator(0);

        String previous = text;
        String source = patch.getSource();
        LinkedList<DiffMatchPatch.Patch> patches = new LinkedList<>(diffMatchPatch.patchFromText(patch.getPatch()));
        Object[] results = diffMatchPatch.patchApply(patches, previous);
        String patched = (String)results[0];
        LinkedList<DiffMatchPatch.Diff> diffs = diffMatchPatch.diffMain(previous, patched);



        DiffMatchPatch.Patch x;
        int start;
        for(DiffMatchPatch.Diff diff: diffs){
            if(current.hasNext()){
                SyncTextDiff next = current.next();
                if(next.length() > diff.text.length()){

                }else if(next.length() < diff.text.length()){

                }else{

                }
                current.a
                SyncTextDiff d = SyncTextDiff.fromDiff(diff, source, null);
                result.add(d);
            } else{
                current.

                if(diff.text.length() > previousDiff.length())
                switch(diff.operation){
                    case DELETE:
                        break;
                    case INSERT:
                        break;
                    case EQUAL:
                        break;
                }
            }
        }
    }

    LinkedList<SyncTextDiff> apply( int start){

    }

    SyncTextDiff apply(SyncTextDiff prev, DiffMatchPatch.Diff current){

    }

    void acceptSuggestions(String source){

    }

    void rejectSuggestions(String source){

    }

}
