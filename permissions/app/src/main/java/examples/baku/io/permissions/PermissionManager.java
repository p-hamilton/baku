// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;


/**
 * Created by phamilton on 6/28/16.
 */
public class PermissionManager {

    DatabaseReference mDatabaseRef;
    DatabaseReference mBlessingsRef;
    DatabaseReference mRequestsRef;

    public static final int FLAG_DEFAULT = 0;
    public static final int FLAG_WRITE = 1 << 0;
    public static final int FLAG_READ = 1 << 1;
    public static final int FLAG_PUSH = 1 << 2;     //2-way
//    public static final int FLAG_REFER = 1 << 3;       //1-way

    static final String KEY_PERMISSIONS = "_permissions";
    static final String KEY_REQUESTS = "_requests";
    static final String KEY_REFERRALS = "_referrals";
    static final String KEY_BLESSINGS = "_blessings";

    private String mId;
    private Blessing rootBlessing;

    final Map<String, PermissionRequest> mRequests = new HashMap<>();
    final Map<String, PermissionRequest> mActiveRequests = new HashMap<>();
    final Map<String, Boolean> mSubscribedRequests = new HashMap<>();

    //    final Map<String, Set<OnRequestListener>> requestListeners = new HashMap<>();
    final Set<OnRequestListener> requestListeners = new HashSet<>();
    final Multimap<String, OnReferralListener> referralListeners = HashMultimap.create();

    final Map<String, Blessing> mBlessings = new HashMap<>();
    //<targetId, blessingId>
    //TODO: allow for multiple granted blessings per target
    final Map<String, Blessing> mGrantedBlessings = new HashMap<>();

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

    public Blessing initRootBlessing() {
        final DatabaseReference deviceBlessingRef = mDatabaseRef.child(KEY_BLESSINGS).child(mId);
        rootBlessing = new Blessing(mId, null, deviceBlessingRef);
        deviceBlessingRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    rootBlessing.setSnapshot(dataSnapshot);
                } else {  //reset
                    rootBlessing.setTarget(mId);
                    rootBlessing.setSource(null);
                }
                refreshPermissions();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
        return rootBlessing;
    }

    void onBlessingUpdated(DataSnapshot snapshot) {
        if (!snapshot.exists()) {
            throw new IllegalArgumentException("snapshot value doesn't exist");
        }
        String key = snapshot.getKey();
        Blessing blessing = mBlessings.get(key);
        if (blessing == null) {
            blessing = new Blessing(snapshot);
            mBlessings.put(key, blessing);
        } else {
            blessing.setSnapshot(snapshot);
        }

        refreshPermissions();
    }

    //TODO: optimize this mess. Currently, recalculating entire permission tree.
    void refreshPermissions() {
        Map<String, Integer> updatedPermissions = new HashMap<>();
        //root blessing
        for (Blessing.Rule rule : rootBlessing) {
            String path = rule.getPath();
            if (mCachedPermissions.containsKey(path)) {
                updatedPermissions.put(path, mCachedPermissions.get(path) | rule.getPermissions());
            } else {
                updatedPermissions.put(path, rule.getPermissions());
            }
        }
        //received blessings
        for (Blessing blessing : mBlessings.values()) {
            if (blessing.isSynched()) {
                for (Blessing.Rule rule : blessing) {
                    String path = rule.getPath();
                    String nearestAncestor = getNearestCommonAncestor(path, mCachedPermissions.keySet());
                    int current = mCachedPermissions.get(nearestAncestor);
                    if(updatedPermissions.containsKey(path)){
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
                Blessing blessing = new Blessing(dataSnapshot);
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

    void onBlessingRemoved(DataSnapshot snapshot) {
        Blessing removedBlessing = mBlessings.remove(snapshot.getKey());
        refreshPermissions();
    }

    static String getNearestCommonAncestor(String path, Set<String> ancestors) {
        if (path != null) {
            String[] pathItems = path.split("/");
            String subpath = null;
            Stack<String> subpaths = new Stack<>();
            for (int i = 0; i < pathItems.length; i++) {
                if (subpath == null) {
                    subpath = subpaths.push(pathItems[i]);
                } else {
                    subpath = subpaths.push(subpath + "/" + pathItems[i]);
                }
            }
            while (!subpaths.empty()) {
                subpath = subpaths.pop();
                if (ancestors.contains(subpath)) {
                    return subpath;
                }
            }
        }

        return null;
    }


    public Blessing getRootBlessing() {
        return rootBlessing;
    }

    public Blessing getBlessing(String target) {
        return mBlessings.get(target);
    }

    //return a blessing interface for granting/revoking permissions
    //uses local device blessing as root
    public Blessing bless(String target) {
        return rootBlessing.bless(target);
    }

    public void revokeBlessing(String target) {
        rootBlessing.revokeBlessing(target);
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

    private void onRequestUpdated(DataSnapshot snapshot) {
        if (!snapshot.exists()) return;

        PermissionRequest request = snapshot.getValue(PermissionRequest.class);
        if (request != null && !mId.equals(request.getSource())) {    //ignore local requests
            mRequests.put(request.getId(), request);
            //TODO: filter relevant requests
            for (OnRequestListener listener : requestListeners) {
                String source = request.getSource();
                if (!mSubscribedRequests.containsKey(request.getId())) {
                    boolean subscribe = listener.onRequest(request, bless(source));
                    mSubscribedRequests.put(request.getId(), subscribe);
                }
            }
        }
    }

    //TODO: only notify listeners that returned true when the request was added
    private void onRequestRemoved(DataSnapshot snapshot) {
        mRequests.remove(snapshot.getKey());
        PermissionRequest request = snapshot.getValue(PermissionRequest.class);
        if (request != null && !mId.equals(request.getSource())) {    //ignore local requests
            for (OnRequestListener listener : requestListeners) {
                String rId = request.getId();
                if (mSubscribedRequests.containsKey(rId) && mSubscribedRequests.get(rId)) {
                    mSubscribedRequests.remove(rId);
                    String source = request.getSource();
                    listener.onRequestRemoved(request, bless(source));
                }
            }
        }
    }


    private ChildEventListener blessingListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            onBlessingUpdated(dataSnapshot);
            mBlessingsRef.orderByChild("source").equalTo(s).addChildEventListener(grantedBlessingListener);
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {
            onBlessingUpdated(dataSnapshot);
        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {
            onBlessingRemoved(dataSnapshot);
        }

        @Override
        public void onChildMoved(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

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

    public void removeOnRequestListener(PermissionManager.OnRequestListener requestListener) {
        requestListeners.remove(requestListener);
    }

    public PermissionManager.OnRequestListener addOnRequestListener(PermissionManager.OnRequestListener requestListener) {
        requestListeners.add(requestListener);
        return requestListener;
    }

    public void removeOnReferralListener(String path, OnReferralListener referralListener) {
        referralListeners.remove(path, referralListener);
    }

    public OnReferralListener addOnReferralListener(String path, OnReferralListener referralListener) {
        referralListeners.put(path, referralListener);
        return referralListener;
    }

    public void refer(String resourcePath, int flags) {

    }

    public void request(String group, PermissionRequest request) {
        if (request == null)
            throw new IllegalArgumentException("null request");

        DatabaseReference requestRef = mRequestsRef.push();
        request.setId(requestRef.getKey());
        request.setSource(mId);
        requestRef.setValue(request);

        cancelRequest(group);   //cancel previous request
        mActiveRequests.put(group, request);
    }

    public void cancelRequest(String group) {
        if (mActiveRequests.containsKey(group)) {
            PermissionRequest request = mActiveRequests.get(group);
            mRequestsRef.child(request.getId()).removeValue();
        }
    }

    public interface OnRequestListener {
        boolean onRequest(PermissionRequest request, Blessing blessing);

        void onRequestRemoved(PermissionRequest request, Blessing blessing);
    }

    public interface OnReferralListener {
        void onReferral();
    }

    public interface OnPermissionChangeListener {
        void onPermissionChange(int current);

        void onCancelled(DatabaseError databaseError);
    }
}
