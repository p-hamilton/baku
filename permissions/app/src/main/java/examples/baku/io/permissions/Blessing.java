// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * Created by phamilton on 7/9/16.
 */
public class Blessing implements Iterable<Blessing.Rule> {

    private static final String KEY_PERMISSIONS = "_permissions";
    private static final String KEY_RULES = "rules";

    private String id;
    //    private String pattern;
    private String source;
    private String target;
    private DatabaseReference ref;
    private DatabaseReference blessingsRef;
    private DatabaseReference rulesRef;
    private DataSnapshot snapshot;

    private Blessing parentBlessing;
    final Map<String, Blessing> grantedBlessings = new HashMap<>();
    final private Map<String, PermissionReference> refCache = new HashMap<>();

    final private Set<OnBlessingUpdatedListener> listeners = new HashSet<>();

    public interface OnBlessingUpdatedListener{
        void onBlessingUpdated(Blessing blessing);
    }

    public Blessing(DataSnapshot snapshot) {
        setRef(snapshot.getRef());
        String target = snapshot.child("target").getValue(String.class);
        String source = null;
        if (snapshot.hasChild("source"))
            source = snapshot.child("source").getValue(String.class);
        setTarget(target);
        setSource(source);
        setSnapshot(snapshot);
    }

    public Blessing(String target, String source, DatabaseReference ref) {
        setRef(ref);
        setId(ref.getKey());
        setSource(source);
        setTarget(target);
    }

    public OnBlessingUpdatedListener addListener(OnBlessingUpdatedListener listener){
        listeners.add(listener);
        return listener;
    }

    public boolean removeListener(OnBlessingUpdatedListener listener){
        return listeners.remove(listener);
    }


    public boolean isSynched() {
        return snapshot != null;
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public void setId(String id) {
        this.id = id;
        ref.child("id").setValue(id);
    }

    public void setSource(String source) {
        this.source = source;
        ref.child("source").setValue(source);
        if(source != null){
            blessingsRef.child(source).addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    if(dataSnapshot.exists()){
                        if(parentBlessing == null){
                            parentBlessing = new Blessing(dataSnapshot);
                        }else{
                            parentBlessing.setSnapshot(dataSnapshot);
                        }
                        notifyListeners();
                    }else{  //destroy self
                        remove();
                    }
                }
                @Override
                public void onCancelled(DatabaseError databaseError) {

                }
            });
        }
    }

    private void notifyListeners(){
        for(OnBlessingUpdatedListener listener : listeners){
            listener.onBlessingUpdated(this);
        }
    }

    public void setTarget(String target) {
        this.target = target;
        ref.child("target").setValue(target);
    }

    public void setSnapshot(DataSnapshot snapshot) {
        if (!snapshot.exists()) {
            throw new IllegalArgumentException("empty snapshot");
        }
        this.snapshot = snapshot;
    }

    public Blessing setPermissions(String path, int permissions) {
        getRef(path).setPermission(permissions);
        return this;
    }

    public Blessing clearPermissions(String path) {
        getRef(path).clearPermission();
        return this;
    }

    public Blessing revoke(){
        ref.removeValue();
        return this;
    }

    //delete all permission above path
    public Blessing revokePermissions(String path) {
        if (path != null) {
            rulesRef.child(path).removeValue();
        } else {  //delete blessing
            rulesRef.removeValue();
        }
        for (Blessing grantedBlessing : grantedBlessings.values()) {
            grantedBlessing.revokePermissions(path);
        }
        return this;
    }

    private void remove() {
        ref.removeValue();
        for (Blessing child : grantedBlessings.values()) {
            child.remove();
        }
    }

    public Blessing revokeBlessing(String target) {
        if (grantedBlessings.containsKey(target)) {
            Blessing revokedBlessing = grantedBlessings.remove(target);
            revokedBlessing.remove();
        }
        return this;
    }

    public PermissionReference getRef(String path) {
        PermissionReference result = null;
        if (refCache.containsKey(path)) {
            result = refCache.get(path);
        } else {
            result = new PermissionReference(rulesRef, path);
            refCache.put(path, result);
        }
        return result;
    }

    private void setRef(DatabaseReference ref) {
        this.ref = ref;
        this.blessingsRef = ref.getParent();
        this.rulesRef = ref.child(KEY_RULES);

        ref.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(dataSnapshot.exists()){
                    setSnapshot(dataSnapshot);
                    notifyListeners();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                setSnapshot(dataSnapshot);

                //get all blessings previously granted by this
                blessingsRef.orderByChild("source").equalTo(id).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        for (DataSnapshot childSnap : dataSnapshot.getChildren()) {
                            Blessing granted = new Blessing(childSnap);
                            grantedBlessings.put(granted.getTarget(), granted);
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {

                    }
                });
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                databaseError.toException().printStackTrace();
            }
        });

    }

    public int getPermissionAt(String path, int starting) {
        if (!isSynched()) {   //snapshot not retrieved
            return starting;
        }

        DataSnapshot currentNode = snapshot.child(KEY_RULES);
        if (currentNode.hasChild(KEY_PERMISSIONS)) {
            starting |= currentNode.child(KEY_PERMISSIONS).getValue(Integer.class);
        }

        if (path == null) { //return root node permissions
            return starting;
        }

        String[] pathItems = path.split("/");
        for (int i = 0; i < pathItems.length; i++) {
            if (currentNode.hasChild(pathItems[i])) {
                currentNode = currentNode.child(pathItems[i]);
            } else {  //child doesn't exist
                break;
            }
            if (currentNode.hasChild(KEY_PERMISSIONS)) {
                starting |= currentNode.child(KEY_PERMISSIONS).getValue(Integer.class);
            }
        }
        if(parentBlessing != null){
            starting &= parentBlessing.getPermissionAt(path, 0);
        }
        return starting;
    }

    //return a blessing interface for granting/revoking permissions
    public Blessing bless(String target) {
        Blessing result = null;
        if (grantedBlessings.containsKey(target)) {
            result = grantedBlessings.get(target);
        } else {
            result = new Blessing(target, this.id, blessingsRef.push());
            grantedBlessings.put(target, result);
        }
        return result;
    }


    @Override
    public Iterator<Rule> iterator() {
        if (!isSynched()) {
            return null;
        }
        final Stack<DataSnapshot> nodeStack = new Stack<>();
        nodeStack.push(snapshot.child(KEY_RULES));

        final Stack<Rule> inheritanceStack = new Stack<>();
        inheritanceStack.push(new Rule(null, 0)); //default rule

        return new Iterator<Rule>() {
            @Override
            public boolean hasNext() {
                return !nodeStack.isEmpty();
            }

            @Override
            public Rule next() {
                DataSnapshot node = nodeStack.pop();
                Rule inheritedRule = inheritanceStack.pop();

                Rule result = new Rule();
                String key = node.getKey();
                if (!KEY_RULES.equals(key)) {   //key_rules is the root directory
                    if (inheritedRule.path != null) {
                        result.path = inheritedRule.path + "/" + key;
                    } else {
                        result.path = key;
                    }
                } 
                result.permissions = inheritedRule.permissions;
                if (node.hasChild(KEY_PERMISSIONS)) {
                    result.permissions |= node.child(KEY_PERMISSIONS).getValue(Integer.class);
                }

                if(parentBlessing != null){
                    //check valid
                    result.permissions &= parentBlessing.getPermissionAt(result.path, 0);
                }

                for (final DataSnapshot child : node.getChildren()) {
                    if (child.getKey().startsWith("_")) { //ignore keys with '_' prefix
                        continue;
                    }
                    nodeStack.push(child);
                    inheritanceStack.push(result);
                }
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public static class Rule {
        private String path;
        private int permissions;

        public Rule() {
        }

        public Rule(String path, int permissions) {
            this.path = path;
            this.permissions = permissions;
        }

        public String getPath() {
            return path;
        }

        public int getPermissions() {
            return permissions;
        }
    }
}
