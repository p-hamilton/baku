// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions;

import com.google.common.base.Optional;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;


/**
 * Created by phamilton on 6/28/16.
 */
public class PermissionManager {

    public static final String EXTRA_TIMEOUT = "extraTimeout";
    public static final String EXTRA_COLOR = "extraColor";
    private DatabaseReference mDatabaseRef;
    private DatabaseReference mBlessingsRef;
    private DatabaseReference mRequestsRef;

    public static final int FLAG_DEFAULT = 0;
    public static final int FLAG_WRITE = 1 << 0;
    public static final int FLAG_READ = 1 << 1;

    static final String KEY_PERMISSIONS = "_permissions";
    static final String KEY_REQUESTS = "_requests";
    static final String KEY_BLESSINGS = "_blessings";

    private static final String KEY_ROOT = "root";

    private String mId;
    private Blessing rootBlessing;

    //<blessing id, blessing>
    final Map<String, Blessing> mBlessings = new HashMap<>();
    //<source, target, blessing>
    final Table<String, String, Blessing> mBlessingsTable = HashBasedTable.create();
//    final Set<Blessing> mGrantedBlessings = new HashSet<>();

    final Map<String, PermissionRequest> mRequests = new HashMap<>();
    final Table<String, String, PermissionRequest.Builder> mActiveRequests = HashBasedTable.create();

    final Multimap<String, OnRequestListener> mRequestListeners = HashMultimap.create(); //<path,, >
    final Multimap<String, OnRequestListener> mSubscribedRequests = HashMultimap.create(); //<request id, >

    final Map<String, Integer> mCachedPermissions = new HashMap<>();
    final Multimap<String, OnPermissionChangeListener> mPermissionValueEventListeners = HashMultimap.create();
    final Multimap<String, String> mNearestAncestors = HashMultimap.create();


    //TODO: replace string ownerId with Auth
    public PermissionManager(final DatabaseReference databaseReference, String owner) {
        this.mDatabaseRef = databaseReference;
        this.mId = owner;

        mRequestsRef = databaseReference.child(KEY_REQUESTS);
        //TODO: only consider requests from sources within the constellation
        mRequestsRef.addChildEventListener(requestListener);
        mBlessingsRef = mDatabaseRef.child(KEY_BLESSINGS);

        this.mId = owner;
        initRootBlessing();
        join(mId);

    }

    public void join(String group) {
        mBlessingsRef.orderByChild("target").equalTo(group).addChildEventListener(blessingListener);
    }

    public void initRootBlessing() {
        rootBlessing = Blessing.createRoot(this, mId);
    }

    //TODO: optimize this mess. Currently, recalculating entire permission tree.
    void refreshPermissions() {
        Map<String, Integer> updatedPermissions = new HashMap<>();
        //received blessings
        for (Blessing blessing : getReceivedBlessings()) {
            if (blessing.isSynched()) {
                for (Blessing.Rule rule : blessing) {
                    String path = rule.getPath();
                    String nearestAncestor = getNearestCommonAncestor(path, updatedPermissions.keySet());
                    Integer current = updatedPermissions.get(nearestAncestor);
                    current = current == null ? 0 : current;
                    if (updatedPermissions.containsKey(path)) {
                        current |= updatedPermissions.get(path);
                    }
                    updatedPermissions.put(path, current | rule.getPermissions());
                }
            }
        }

        mNearestAncestors.clear();
        for (String path : mPermissionValueEventListeners.keySet()) {
            String nearestAncestor = getNearestCommonAncestor(path, updatedPermissions.keySet());
            if (nearestAncestor != null) {
                mNearestAncestors.put(nearestAncestor, path);
            }
        }

        Set<String> changedPermissions = new HashSet<>();

        Set<String> removedPermissions = new HashSet<>(mCachedPermissions.keySet());
        removedPermissions.removeAll(updatedPermissions.keySet());
        for (String path : removedPermissions) {
            mCachedPermissions.remove(path);
            String newPath = getNearestCommonAncestor(path, updatedPermissions.keySet());
            changedPermissions.add(newPath);   //reset to default
        }

        for (String path : updatedPermissions.keySet()) {
            int current = updatedPermissions.get(path);
            if (!mCachedPermissions.containsKey(path)) {
                mCachedPermissions.put(path, current);
                changedPermissions.add(path);
            } else {
                int previous = mCachedPermissions.get(path);
                if (previous != current) {
                    mCachedPermissions.put(path, current);
                    changedPermissions.add(path);
                }
            }
        }
        for (String path : changedPermissions) {
            onPermissionsChange(path);
        }
    }

    //call all the listeners effected by a permission change at this path
    void onPermissionsChange(String path) {
        int permission = getPermission(path);
        if (mNearestAncestors.containsKey(path)) {
            for (String listenerPath : mNearestAncestors.get(path)) {
                if (mPermissionValueEventListeners.containsKey(listenerPath)) {
                    for (OnPermissionChangeListener listener : mPermissionValueEventListeners.get(listenerPath)) {
                        listener.onPermissionChange(permission);
                    }
                }
            }
        }
    }

    private ChildEventListener grantedBlessingListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            if (dataSnapshot.exists()) {
                Blessing grantedBlessing = Blessing.fromSnapshot(PermissionManager.this, dataSnapshot);
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

    static String getNearestCommonAncestor(String path, Set<String> ancestors) {
        if (path == null || ancestors.contains(path)) {
            return path;
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("Path can't start with /");
        }
        String subpath = path;
        int index;
        while ((index = subpath.lastIndexOf("/")) != -1) {
            subpath = subpath.substring(0, index);
            if (ancestors.contains(subpath)) {
                return subpath;
            }
        }

        return null;
    }

    public Set<PermissionRequest> getRequests(String path) {
        Set<PermissionRequest> result = new HashSet<>();
        for (PermissionRequest request : mRequests.values()) {
            if (getAllPaths(request.getPath()).contains(path)) {
                result.add(request);
            }
        }
        return result;
    }


    public Blessing getRootBlessing() {
        return rootBlessing;
    }

    public Collection<Blessing> getReceivedBlessings() {
        return mBlessingsTable.column(mId).values();
    }

    public Collection<Blessing> getGrantedBlessings(String source) {
        return mBlessingsTable.row(mId).values();
    }

    public Blessing putBlessing(String source, String target, Blessing blessing) {
        mBlessings.put(blessing.getId(), blessing);
        if(source == null)
            source = KEY_ROOT;
        return mBlessingsTable.put(source, target, blessing);
    }

    public Blessing getBlessing(String id) {
        return mBlessings.get(id);
    }

    public Blessing getBlessing(String source, String target) {
        if(source == null)
            source = KEY_ROOT;
        return mBlessingsTable.get(source, target);
    }

    public void removeBlessing(String rId) {
        Blessing removedBlessing = mBlessings.remove(rId);
        if (removedBlessing != null) {
            mBlessingsTable.remove(removedBlessing.getSource(), removedBlessing.getTarget());

        }
    }

    //return a blessing interface for granting/revoking permissions
    //uses local device blessing as root
    public Blessing bless(String target) {
        return rootBlessing.bless(target);
    }

    public DatabaseReference getBlessingsRef() {
        return mBlessingsRef;
    }

    private ChildEventListener requestListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            onRequestUpdated(dataSnapshot);
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {
            onRequestUpdated(dataSnapshot);
        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {
            onRequestRemoved(dataSnapshot);
        }

        @Override
        public void onChildMoved(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };

    public void finishRequest(String rId) {
        //TODO: notify source entity and ignore instead of removing
        mRequestsRef.child(rId).removeValue();
    }

    public void grantRequest(PermissionRequest request) {
        Blessing blessing = bless(request.getSource());
        blessing.setPermissions(request.getPath(), request.getPermissions());
        finishRequest(request.getId());
    }

    private void onRequestUpdated(DataSnapshot snapshot) {
        if (!snapshot.exists()) return;

        PermissionRequest request = snapshot.getValue(PermissionRequest.class);
        if (request != null && request.getPath() != null) {
            //ignore local requests
            if (mId.equals(request.getSource()))
                return;
            //Check if request permissions can be granted by this instance
            if ((getPermission(request.getPath()) & request.getPermissions()) != request.getPermissions()) {
                return;
            }

            String rId = request.getId();
            String source = request.getSource();
            mRequests.put(rId, request);

            if (mSubscribedRequests.containsKey(rId)) {
                for (OnRequestListener listener : new HashSet<>(mSubscribedRequests.get(rId))) {
                    if (!listener.onRequest(request, bless(source))) {
                        //cancel subscription
                        mSubscribedRequests.remove(rId, listener);
                    }
                }
            } else {
                for (String path : getAllPaths(request.getPath())) {
                    for (OnRequestListener listener : mRequestListeners.get(path)) {
                        if (listener.onRequest(request, bless(source))) {
                            //add subscription
                            mSubscribedRequests.put(request.getId(), listener);
                        }
                    }
                }
            }
        }
    }

    private void onRequestRemoved(DataSnapshot snapshot) {
        mRequests.remove(snapshot.getKey());
        PermissionRequest request = snapshot.getValue(PermissionRequest.class);
        String source = request.getSource();
        if (request != null && !mId.equals(source)) {    //ignore local requests
            for (OnRequestListener listener : new HashSet<>(mSubscribedRequests.get(request.getId()))) {
                mSubscribedRequests.remove(request.getId(), listener);
                listener.onRequestRemoved(request, bless(source));
            }
        }
    }

    //allows
    private Set<String> getAllPaths(String path) {
        Set<String> result = new HashSet<>();
        result.add(path);
        result.add("*");
        String subpath = path;
        int index;
        while ((index = subpath.lastIndexOf("/")) != -1) {
            subpath = subpath.substring(0, index);
            result.add(subpath + "/*");
        }
        return result;
    }

    private ChildEventListener blessingListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot snapshot, String s) {
            Blessing receivedBlessing = Blessing.fromSnapshot(PermissionManager.this, snapshot);
            receivedBlessing.addListener(blessingChangedListner);
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {
        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {
            Blessing removedBlessing = mBlessings.remove(dataSnapshot.getKey());
            if (removedBlessing != null) {
                removedBlessing.removeListener(blessingChangedListner);
                mBlessingsTable.remove(removedBlessing.getSource(), removedBlessing.getTarget());
                refreshPermissions();
            }
        }

        @Override
        public void onChildMoved(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };

    private Blessing.OnBlessingUpdatedListener blessingChangedListner = new Blessing.OnBlessingUpdatedListener() {
        @Override
        public void onBlessingUpdated(Blessing blessing) {
            refreshPermissions();
        }

        @Override
        public void onBlessingRemoved(Blessing blessing) {
            refreshPermissions();
        }
    };


    public int getPermission(String path) {
        if (mCachedPermissions.containsKey(path))
            return mCachedPermissions.get(path);
        int result = getCombinedPermission(path);
        mCachedPermissions.put(path, result);
        return result;
    }

    private int getCombinedPermission(String path) {
        int current = 0;
        for (Blessing blessing : mBlessings.values()) {
            current = blessing.getPermissionAt(path, current);
        }
        return current;
    }

    public OnPermissionChangeListener addPermissionEventListener(String path, OnPermissionChangeListener listener) {
        int current = FLAG_DEFAULT;
        mPermissionValueEventListeners.put(path, listener);

        String nearestAncestor = getNearestCommonAncestor(path, mCachedPermissions.keySet());
        if (nearestAncestor != null) {
            current = getPermission(nearestAncestor);
            mNearestAncestors.put(nearestAncestor, path);
        }
        listener.onPermissionChange(current);
        return listener;
    }

    public void removePermissionEventListener(String path, OnPermissionChangeListener listener) {
        mPermissionValueEventListeners.remove(path, listener);
        String nca = getNearestCommonAncestor(path, mCachedPermissions.keySet());
        mNearestAncestors.remove(nca, path);

    }


    public void removeOnRequestListener(String path, OnRequestListener requestListener) {
        mRequestListeners.remove(path, requestListener);
        for (String key : mSubscribedRequests.keySet()) {
            mSubscribedRequests.remove(key, requestListener);
        }
    }

    public OnRequestListener addOnRequestListener(String path, OnRequestListener requestListener) {
        mRequestListeners.put(path, requestListener);
        for (String rId : mRequests.keySet()) {
            PermissionRequest request = mRequests.get(rId);
            if (request != null) {
                String source = request.getSource();
                if (requestListener.onRequest(request, bless(source))) {
                    mSubscribedRequests.put(rId, requestListener);
                }
            }
        }
        return requestListener;
    }

    public PermissionRequest.Builder request(String path, String group) {
        PermissionRequest.Builder builder = mActiveRequests.get(group, path);
        if (builder == null) {
            builder = new PermissionRequest.Builder(mRequestsRef.push(), path, mId);
            mActiveRequests.put(group, path, builder);
        }
        return builder;
    }

    public void cancelRequests(String group) {
        for (String path : new HashSet<String>(mActiveRequests.row(group).keySet())) {
            cancelRequest(group, path);
        }
    }

    public void cancelRequest(String group, String path) {
        PermissionRequest.Builder builder = mActiveRequests.remove(group, path);
        if (builder != null) {
            builder.cancel();
        }
    }

    public interface OnRequestListener {
        boolean onRequest(PermissionRequest request, Blessing blessing);

        void onRequestRemoved(PermissionRequest request, Blessing blessing);
    }

    public interface OnPermissionChangeListener {
        void onPermissionChange(int current);

        void onCancelled(DatabaseError databaseError);
    }
}
