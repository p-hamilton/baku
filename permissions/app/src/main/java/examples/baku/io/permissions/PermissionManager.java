package examples.baku.io.permissions;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
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
    static final String KEY_BLESSINGS = "_blessings";

    private String mId;

    final Map<String, PermissionRequest> mRequests = new HashMap<>();

//    final Map<String, Set<OnRequestListener>> requestListeners = new HashMap<>();
    final Set<OnRequestListener> requestListeners = new HashSet<>();
    final Map<String, Set<OnReferralListener>> referralListeners = new HashMap<>();

    final Map<String, Blessing> mBlessings = new HashMap<>();
    //<targetId, blessingId>
    //TODO: allow for multiple granted blessings per target
    final Map<String, Blessing> mGrantedBlessings = new HashMap<>();

    final Map<String, Integer> mCachedPermissions = new HashMap<>();
    final Map<String, Set<OnPermissionChangeListener>> mPermissionValueEventListeners = new HashMap<>();
    final Map<String, Set<String>> mNearestAncestors = new HashMap<>();




    //TODO: replace string ownerId with Auth
    public PermissionManager(final DatabaseReference databaseReference, String owner) {
        this.mDatabaseRef = databaseReference;
        this.mId = owner;

        mRequestsRef = databaseReference.child(KEY_REQUESTS);
        //TODO: only consider requests from sources within the constelattion
        mRequestsRef.addChildEventListener(requestListener);

        mBlessingsRef = mDatabaseRef.child(KEY_BLESSINGS);
        mBlessingsRef.orderByChild("target").equalTo(mId).addChildEventListener(blessingListener);
        mBlessingsRef.orderByChild("source").equalTo(mId).addListenerForSingleValueEvent(grantedBlessingListener);
    }

    void onBlessingUpdated(DataSnapshot snapshot) {
        if (!snapshot.exists()) {
            throw new IllegalArgumentException("snapshot value doesn't exist");
        }
        String key = snapshot.getKey();
        Blessing blessing = null;
        if (mBlessings.containsKey(key)) {
            blessing = mBlessings.get(key);
            blessing.setSnapshot(snapshot);
        } else {
            blessing = new Blessing(snapshot);
            mBlessings.put(key, blessing);
        }
        refreshPermissions();
    }

    //TODO: optimize this mess. Currently, recalculating entire permission tree.
    void refreshPermissions() {
        Map<String, Integer> updatedPermissions = new HashMap<>();
        for (Blessing blessing : mBlessings.values()) {
            if(blessing.isSynched()){
                for (Blessing.Rule rule : blessing) {
                    String path = rule.getPath();
                    if (updatedPermissions.containsKey(path)) {
                        updatedPermissions.put(path, updatedPermissions.get(path) | rule.getPermissions());
                    } else {
                        updatedPermissions.put(path, rule.getPermissions());
                    }
                }
            }
        }

        mNearestAncestors.clear();
        for (String path : mPermissionValueEventListeners.keySet()) {
            String nearestAncestor = getNearestCommonAncestor(path, updatedPermissions.keySet());
            if (nearestAncestor != null) {
                if (mNearestAncestors.containsKey(nearestAncestor)) {
                    mNearestAncestors.get(nearestAncestor).add(path);
                } else {
                    Set<String> descendants = new HashSet<>();
                    descendants.add(path);
                    mNearestAncestors.put(nearestAncestor, descendants);
                }
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

         for(String path: changedPermissions){
            onPermissionsChange(path);
        }



    }

    //call all the listeners effected by a permission change at this path
    void onPermissionsChange(String path) {
        if (mNearestAncestors.containsKey(path)) {
            for (String listenerPath : mNearestAncestors.get(path)) {
                if (mPermissionValueEventListeners.containsKey(listenerPath)) {
                    for (OnPermissionChangeListener listener : mPermissionValueEventListeners.get(listenerPath)) {
                        listener.onPermissionChange(getPermission(path));
                    }
                }
            }
        }
    }

    private ValueEventListener grantedBlessingListener = new ValueEventListener() {
        @Override
        public void onDataChange(DataSnapshot dataSnapshot) {
            if(dataSnapshot.exists()){
                for(DataSnapshot blessingSnap : dataSnapshot.getChildren()){
                    Blessing blessing = new Blessing(blessingSnap);
                    mGrantedBlessings.put(blessing.getId(), blessing);
                }
            }
        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };

    void onBlessingRemoved(DataSnapshot snapshot) {
        Blessing removedBlessing = mBlessings.remove(snapshot.getKey());
        refreshPermissions();
    }

    public Blessing getGrantedBlessing(String target) {
        if (mGrantedBlessings.containsKey(target)) {
            return mGrantedBlessings.get(target);
        }
        return null;
    }

    static String getNearestCommonAncestor(String path, Set<String> ancestors) {
        String[] pathItems = path.split("/");
        String subpath = null;
        Stack<String> subpaths = new Stack<>();
        for (int i = 0; i < pathItems.length; i++) {
            if(subpath == null){
                subpath = subpaths.push(pathItems[i]);
            }else{
                subpath = subpaths.push(subpath + "/" + pathItems[i]);
            }
        }
        while (!subpaths.empty()) {
            subpath = subpaths.pop();
            if (ancestors.contains(subpath)) {
                return subpath;
            }
        }
        return null;
    }

    //return a blessing interface for granting/revoking permissions
    public Blessing bless(String target) {
        Blessing result = getGrantedBlessing(target);
        if (result == null) {
            result = new Blessing(target, this.mId, mBlessingsRef.push());
            mGrantedBlessings.put(target, result);
        }
        return result;
    }

    private ChildEventListener requestListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            onBlessingUpdated(dataSnapshot);
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

    private void onRequestUpdated(DataSnapshot snapshot){
        if(!snapshot.exists()) return;

        PermissionRequest request = snapshot.getValue(PermissionRequest.class);
        if(request != null){
            mRequests.put(request.getId(), request);
            //TODO: filter relevant requests
            for (OnRequestListener listener: requestListeners){
                listener.onRequest(request);
            }
        }
    }

    //TODO: only notify listeners that returned true when the request was added
    private void onRequestRemoved(DataSnapshot snapshot){
        mRequests.remove(snapshot.getKey());
        PermissionRequest request = snapshot.getValue(PermissionRequest.class);
        if(request != null){
            for (OnRequestListener listener: requestListeners){
                listener.onRequestRemoved(request);
            }
        }
    }


    private ChildEventListener blessingListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            onBlessingUpdated(dataSnapshot);
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
        if (mPermissionValueEventListeners.containsKey(path)) {
            mPermissionValueEventListeners.get(path).add(listener);
        } else {
            Set<OnPermissionChangeListener> listeners = new HashSet<OnPermissionChangeListener>();
            listeners.add(listener);
            mPermissionValueEventListeners.put(path, listeners);
        }
        String nearestAncestor = getNearestCommonAncestor(path, mCachedPermissions.keySet());
        if (nearestAncestor != null) {
            current = getPermission(nearestAncestor);
            if (mNearestAncestors.containsKey(nearestAncestor)) {
                mNearestAncestors.get(nearestAncestor).add(path);
            } else {
                Set<String> descendants = new HashSet<>();
                descendants.add(path);
                mNearestAncestors.put(nearestAncestor, descendants);
            }
        }
        listener.onPermissionChange(current);
        return listener;
    }

    public void removePermissionEventListener(String path, OnPermissionChangeListener listener) {
        if (mPermissionValueEventListeners.containsKey(path)) {
            mPermissionValueEventListeners.get(path).remove(listener);
        }
        String nca = getNearestCommonAncestor(path, mCachedPermissions.keySet());
        if (mNearestAncestors.containsKey(nca)) {
            mNearestAncestors.get(nca).remove(path);
        }
    }

    public void removeOnRequestListener(PermissionManager.OnRequestListener requestListener) {
        requestListeners.remove(requestListener);
    }

    public PermissionManager.OnRequestListener addOnRequestListener(PermissionManager.OnRequestListener requestListener) {
        requestListeners.add(requestListener);
        return requestListener;
    }

    public void removeOnReferralListener(OnReferralListener referralListener) {
        referralListeners.remove(referralListener);
    }

    public OnReferralListener addOnReferralListener(String path, OnReferralListener referralListener) {
        if (referralListeners.containsKey(path)) {
            referralListeners.get(path).add(referralListener);
        } else {
            Set<OnReferralListener> listeners = new HashSet<>();
            listeners.add(referralListener);
            referralListeners.put(path, listeners);
        }
        return referralListener;
    }

    public void refer(PermissionReferral referral){
    }

    public void request(PermissionRequest request) {
        if(request == null)
            throw new IllegalArgumentException("null request");

        DatabaseReference requestRef = mRequestsRef.push();
        request.setId(requestRef.getKey());
        requestRef.setValue(request);
    }

    public interface OnRequestListener {
        boolean onRequest(PermissionRequest request);
        void onRequestRemoved(PermissionRequest request);
    }

    public interface OnReferralListener {
        void onReferral();
    }

    public interface OnPermissionChangeListener {
        void onPermissionChange(int current);

        void onCancelled(DatabaseError databaseError);
    }
}
