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
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import examples.baku.io.permissions.util.Utils;

/**
 * Created by phamilton on 7/9/16.
 */
public class Blessing implements Iterable<Blessing.Permission>, ValueEventListener {

    private static final String KEY_PERMISSIONS = "_permissions";
    private static final String KEY_RULES = "rule";

    private PermissionManager permissionManager;

    private String id;
    private String source;
    private String target;
    private DatabaseReference ref;
    private DatabaseReference rulesRef;
    private DataSnapshot snapshot;

    private Blessing parentBlessing;
    final private Map<String, Integer> permissions = new HashMap<>();
    private final PermissionTree permissionTree = new PermissionTree();

    final private Set<OnBlessingUpdatedListener> blessingListeners = new HashSet<>();


    public interface OnBlessingUpdatedListener {
        void onBlessingUpdated(Blessing blessing);

        void onBlessingRemoved(Blessing blessing);
    }

    private Blessing(PermissionManager permissionManager, String id, String source, String target) {
        this.permissionManager = permissionManager;
        if (id == null) {
//            setRef(permissionManager.getBlessingsRef().push());
            //TEMP: use a combination of source and target for debugging
            setRef(permissionManager.getBlessingsRef().child(source + "_" + target));
            id = this.ref.getKey();

        } else {
            setRef(permissionManager.getBlessingsRef().child(id));
        }
        setId(id);
        setSource(source);
        setTarget(target);

        permissionManager.putBlessing(source, target, this);
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
        listener.onBlessingUpdated(this);
        return listener;
    }

    public boolean addListeners(Collection<OnBlessingUpdatedListener> listeners) {
        return this.blessingListeners.addAll(listeners);
    }

    public boolean removeListener(OnBlessingUpdatedListener listener) {
        return blessingListeners.remove(listener);
    }

    public boolean removeListeners(Collection<OnBlessingUpdatedListener> listeners) {
        return blessingListeners.removeAll(listeners);
    }

    private OnBlessingUpdatedListener parentListener = new OnBlessingUpdatedListener() {
        @Override
        public void onBlessingUpdated(Blessing blessing) {
            permissionTree.parentTree = parentBlessing.permissionTree;
            notifyListeners();
        }

        @Override
        public void onBlessingRemoved(Blessing blessing) {
            //revoke self
            revoke();
        }
    };

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
            if (parentBlessing == null) { //retrieve, if manager isn't tracking blessing
                permissionManager.getBlessingsRef().child(source).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (dataSnapshot.exists()) {
                            parentBlessing = Blessing.fromSnapshot(permissionManager, dataSnapshot);
                            parentBlessing.addListener(parentListener);
                        } else {  //destroy self if source doesn't exist
                            revoke();
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {

                    }
                });
            } else {
                permissionTree.parentTree = parentBlessing.permissionTree;
                parentBlessing.addListener(parentListener);
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
        if (snapshot.hasChild(KEY_RULES)) {
            this.permissionTree.setRoot(new Permission(snapshot.child(KEY_RULES), null, 0));
        } else {
            this.permissionTree.setRoot(new Permission());
        }
    }

    public Blessing setPermissions(String path, int permissions) {
        this.permissions.put(path, permissions);
        getRef(path).setPermission(permissions);
        return this;
    }

    public void setPermissions(Map<String, Integer> permissions) {
        for (String path : permissions.keySet()) {
            setPermissions(path, permissions.get(path));
        }
    }

    public Blessing clearPermissions(String path) {
        getRef(path).clearPermission();
        this.permissions.remove(path);
        return this;
    }

    public Blessing revoke() {
        if (parentBlessing != null) {
            parentBlessing.removeListener(parentListener);
        }
        for (OnBlessingUpdatedListener listener : new HashSet<>(blessingListeners)) {
            listener.onBlessingRemoved(this);
        }
        ref.removeEventListener(this);
        rulesRef.removeValue();
        return this;
    }

    //delete all permission above path
    public Blessing revokePermissions(String path) {
        if (path != null) {
            rulesRef.child(path).removeValue();
        } else {
            rulesRef.removeValue();
        }
        return this;
    }

    private PermissionReference getRef(String path) {
        return new PermissionReference(rulesRef, path);
    }

    private void setRef(DatabaseReference ref) {
        this.ref = ref;
        this.rulesRef = ref.child(KEY_RULES);

        ref.addValueEventListener(this);
    }

    @Override
    public void onDataChange(DataSnapshot dataSnapshot) {
        if (dataSnapshot.exists()) {
            setSnapshot(dataSnapshot);
            notifyListeners();
        }
    }

    @Override
    public void onCancelled(DatabaseError databaseError) {
        databaseError.toException().printStackTrace();
    }

    //return a blessing interface for granting/revoking permissions
    public Blessing bless(String target) {
        Blessing result = getBlessing(target);
        if (result == null) {
            if (descendantOf(target)) {
                throw new IllegalArgumentException("Can't bless a target that already exists in the blessing hiearchy.");
            }
            result = Blessing.create(permissionManager, getId(), target);
        }
        return result;
    }

    public Blessing getBlessing(String target) {
        return permissionManager.getBlessing(getId(), target);
    }

    public boolean descendantOf(String target) {
        if (this.target.equals(target)) {
            return true;
        }
        if (this.parentBlessing != null) {
            return this.parentBlessing.descendantOf(target);
        }
        return false;
    }


    @Override
    public Iterator<Permission> iterator() {
        if (!isSynched()) {
            return null;
        }
        return permissionTree.iterator();
    }

    public PermissionTree getPermissionTree() {
        return permissionTree;
    }


    public static class Permission implements Iterable<Permission> {
        String key;
        String path;
        int inherited;
        int permissions;
        final Map<String, Permission> children = new HashMap();


        public Permission() {
        }

        public Permission(DataSnapshot snapshot, String path, int inherited) {
            this.path = path;
            if (path != null)
                this.key = snapshot.getKey();
            this.inherited = inherited;
            if (snapshot.hasChild(KEY_PERMISSIONS)) {
                this.permissions |= snapshot.child(KEY_PERMISSIONS).getValue(Integer.class);
            }
            for (DataSnapshot child : snapshot.getChildren()) {
                if (child.getKey().startsWith("_")) { //ignore keys with '_' prefix
                    continue;
                }
                String childPath = child.getKey();
                if (path != null) {
                    childPath = path + "/" + childPath;
                }
                children.put(child.getKey(), new Permission(child, childPath, this.permissions | this.inherited));
            }
        }

        public Permission copy() {
            Permission result = new Permission();
            result.key = key;
            result.path = path;
            result.inherited = inherited;
            result.permissions = permissions;
            for (Permission child : new HashSet<>(children.values())) {
                result.children.put(child.key, child.copy());
            }
            return result;
        }

        public void addPermissions(int permission) {
            if ((this.permissions ^ permission) != 0) {
                this.permissions |= permission;
                for (Permission child : children.values()) {
                    child.setInherited(getPermissions());
                }
            }
        }

        public void setInherited(int permission) {
            if (this.inherited != permission) {
                this.inherited = permission;
                for (Permission child : children.values()) {
                    child.setInherited(getPermissions());
                }
            }
        }

        public void removePermissions(int permission) {
            this.permissions &= ~(permission);
            for (Permission child : children.values()) {
                child.setInherited(this.permissions | inherited);
            }
        }

        public void checkPermissions(int reference) {
            this.permissions &= reference;
            for (Permission child : children.values()) {
                child.setInherited(this.permissions | inherited);
            }
        }

        public void checkPermissions(PermissionTree ref) {
            for (Permission permission : this) {
                permission.permissions &= ref.getPermissions(permission.path);
            }
            setInherited(inherited & ref.getPermissions(path));
        }


        public Permission child(String path) {
            if (path.contains("/")) {
                Permission result = this;
                String subpath;
                int start = 0;
                int end;
                while ((end = path.indexOf("/", start)) == -1) {
                    subpath = path.substring(start, end);
                    start = end;
                    result = result.children.get(subpath);
                    if (result == null) {
                        return null;
                    }
                }
                return result;
            }
            return children.get(path);
        }

        public int getPermissions() {
            return permissions | inherited;
        }


        @Override
        public Iterator<Permission> iterator() {
            final Stack<Permission> nodeStack = new Stack<>();
            nodeStack.push(this);

            final Stack<String> pathStack = new Stack<>();
            pathStack.push(null); //default rule

            return new Iterator<Permission>() {
                @Override
                public boolean hasNext() {
                    return !nodeStack.isEmpty();
                }

                @Override
                public Permission next() {
                    Permission node = nodeStack.pop();
                    for (final Permission child : node.children.values()) {
                        nodeStack.push(child);
                    }
                    return node;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }


    public static class PermissionTree implements Iterable<Permission> {
        Permission root;
        final Map<String, Permission> rules = new HashMap<>();
        PermissionTree parentTree;

        public PermissionTree(DataSnapshot snapshot) {
            setRoot(new Permission(snapshot, null, 0));
        }

        public PermissionTree() {
            setRoot(new Permission());
        }

        public void setRoot(Permission root) {
            this.root = root;
            updateRules();
        }

        public void merge(PermissionTree tree) {
            Permission permissionA;
            Permission permissionB = tree.root.copy();
            permissionB.checkPermissions(tree);   //check no permissions exceed parent
            Queue<Permission> permissionQueue = new LinkedList<>();
            permissionQueue.add(permissionB);

            Set<String> shared = new HashSet<>(rules.keySet());
            shared.retainAll(tree.rules.keySet());

            while (!permissionQueue.isEmpty()) {
                permissionB = permissionQueue.remove();
                permissionA = rules.get(permissionB.path);
                permissionA.addPermissions(tree.getPermissions(permissionB.path));
                for (Permission child : permissionB.children.values()) {
                    if (shared.contains(child.path)) {
                        permissionQueue.add(child);
                    } else {
                        child.setInherited(permissionA.getPermissions());
                        permissionA.children.put(child.key, child);
                    }
                }
            }
            updateRules();
        }

        private void updateRules() {
            rules.clear();
            for (Permission permission : root) {
                rules.put(permission.path, permission);
            }
        }

        public Permission get(String path) {
            return rules.get(path);
        }

        public int getPermissions(String path) {
            path = Utils.getNearestCommonAncestor(path, new HashSet<String>(keySet()));
            Permission permission = get(path);
            if (permission == null) return 0;
            int result = permission.getPermissions();
            if (parentTree != null) { //validate
                result &= parentTree.getPermissions(path);
            }
            return result;
        }

        public Set<String> keySet() {
            return rules.keySet();
        }

        public Collection<Permission> values() {
            return rules.values();
        }

        @Override
        public Iterator<Permission> iterator() {
            return root.iterator();
        }
    }
}
