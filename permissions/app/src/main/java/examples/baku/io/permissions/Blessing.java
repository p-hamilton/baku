// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Collection;
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

    private PermissionManager permissionManager;

    private String id;
    //    private String pattern;
    private String source;
    private String target;
    private DatabaseReference ref;
    //    private DatabaseReference blessingsRef;
    private DatabaseReference rulesRef;
    private DataSnapshot snapshot;

    private Blessing parentBlessing;
//    final Map<String, Blessing> grantedBlessings = new HashMap<>();
    final private Map<String, PermissionReference> refCache = new HashMap<>();

    final private Set<OnBlessingUpdatedListener> blessingListeners = new HashSet<>();

    public interface OnBlessingUpdatedListener {
        void onBlessingUpdated(Blessing blessing);
        void onBlessingRemoved(Blessing blessing);
    }

    private Blessing(PermissionManager permissionManager, String id, String source, String target) {
        this.permissionManager = permissionManager;
        permissionManager.putBlessing(source, target, this);
        if (id == null) {
//            setRef(permissionManager.getBlessingsRef().push());
            //TEMP: use a combination of source and target for debugging
            setRef(permissionManager.getBlessingsRef().child(source + "__" + target));
            id = this.ref.getKey();

        } else {
            setRef(permissionManager.getBlessingsRef().child(id));
        }
        setId(id);
        setSource(source);
        setTarget(target);
    }

    public static Blessing create(PermissionManager permissionManager, String source, String target) {
        Blessing blessing = permissionManager.getBlessing(source, target);
        if (blessing == null) {
            blessing = new Blessing(permissionManager, null, source, target);
        }
        return blessing;
    }

    //root blessings have no source blessing and their id is the same as their target
    public static Blessing createRoot(PermissionManager permissionManager, String target) {
        Blessing blessing = permissionManager.getBlessing(null, target);
        if (blessing == null) {
            blessing = new Blessing(permissionManager, target, null, target);
        }
        return blessing;
    }

    public static Blessing fromSnapshot(PermissionManager permissionManager, DataSnapshot snapshot) {
        String id = snapshot.getKey();
        String target = snapshot.child("target").getValue(String.class);
        String source = null;
        if (snapshot.hasChild("source"))
            source = snapshot.child("source").getValue(String.class);
        Blessing blessing = permissionManager.getBlessing(source, target);
        if (blessing == null) {
            blessing = new Blessing(permissionManager, id, source, target);
        }
        return blessing;
    }

    public OnBlessingUpdatedListener addListener(OnBlessingUpdatedListener listener) {
        blessingListeners.add(listener);
        return listener;
    }

    public boolean addListeners(Collection<OnBlessingUpdatedListener> listeners) {
        return this.blessingListeners.addAll(listeners);
    }

    public boolean removeListener(OnBlessingUpdatedListener listener) {
        if(parentBlessing != null){
            parentBlessing.removeListener(listener);
        }
        return blessingListeners.remove(listener);
    }

    public boolean removeListeners(Collection<OnBlessingUpdatedListener> listeners){
        if(parentBlessing != null){
            parentBlessing.removeListeners(listeners);
        }
        return blessingListeners.removeAll(listeners);
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

    private void setSource(String source) {
        if (this.source == null && source != null) {
            this.source = source;
            ref.child("source").setValue(source);
            parentBlessing = permissionManager.getBlessing(source);

            if(parentBlessing == null){ //retrieve, if manager isn't tracking blessing
                permissionManager.getBlessingsRef().child(source).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (dataSnapshot.exists()) {
                            parentBlessing = Blessing.fromSnapshot(permissionManager, dataSnapshot);
                            parentBlessing.addListeners(blessingListeners);
                        } else {  //destroy self if source doesn't exist
                            revoke();
                        }
                    }
                    @Override
                    public void onCancelled(DatabaseError databaseError) {

                    }
                });
            }else{
                parentBlessing.addListeners(blessingListeners);
            }
        }

    }

    private void notifyListeners() {
        for (OnBlessingUpdatedListener listener : blessingListeners) {
            listener.onBlessingUpdated(this);
        }
    }

    public void setTarget(String target) {
        this.target = target;
        ref.child("target").setValue(target);
    }

    private void setSnapshot(DataSnapshot snapshot) {
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

    public Blessing revoke() {
        if(parentBlessing != null){
            parentBlessing.removeListeners(this.blessingListeners);
        }
        for(OnBlessingUpdatedListener listener : blessingListeners){
            listener.onBlessingRemoved(this);
        }
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
        return this;
    }

    private PermissionReference getRef(String path) {
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
        this.rulesRef = ref.child(KEY_RULES);

        ref.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    setSnapshot(dataSnapshot);
                    notifyListeners();
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {

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
        if (parentBlessing != null) {
            starting &= parentBlessing.getPermissionAt(path, 0);
        }
        return starting;
    }

    //return a blessing interface for granting/revoking permissions
    public Blessing bless(String target) {
        Blessing result = permissionManager.getBlessing(getId(), target);
        if(result == null){
            result = Blessing.create(permissionManager, getId(), target);
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

                if (parentBlessing != null) {
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
