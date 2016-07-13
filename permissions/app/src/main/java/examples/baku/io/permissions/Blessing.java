package examples.baku.io.permissions;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;

/**
 * Created by phamilton on 7/9/16.
 *
 *
 */
public class Blessing implements Iterable<Blessing.Rule> {

    static final String KEY_PERMISSIONS = "_permissions";
    static final String KEY_RULES = "rules";

    private String id;
    //    private String pattern;
    private String source;
    private String target;
    private DatabaseReference ref;
    private DatabaseReference rulesRef;
    private DataSnapshot snapshot;

    final private Map<String, PermissionReference> refCache = new HashMap<>();

    public Blessing(DataSnapshot snapshot) {
        setSnapshot(snapshot);
        this.id = snapshot.child("id").getValue(String.class);
        this.target = snapshot.child("target").getValue(String.class);
        if (snapshot.hasChild("source"))
            this.source = snapshot.child("source").getValue(String.class);
    }

    public Blessing(String target, String source, DatabaseReference ref) {
        setRef(ref);
        setId(ref.getKey());
        setSource(source);
        setTarget(target);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                setSnapshot(dataSnapshot);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                databaseError.toException().printStackTrace();
            }
        });
    }

    public boolean isSynched(){
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
        setRef(snapshot.getRef());
    }

    public Blessing setPermissions(String path, int permissions) {
        getRef(path).setPermission(permissions);
        return this;
    }

    public Blessing clearPermissions(String path) {
        getRef(path).clearPermission();
        return this;
    }

    //delete all permission above path
    public Blessing revoke(String path) {
        if(path != null){
            rulesRef.child(path).removeValue();
        }else{
            rulesRef.removeValue();
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

    public void setRef(DatabaseReference ref) {
        this.ref = ref;
        this.rulesRef = ref.child(KEY_RULES);
    }

    public int getPermissionAt(String path, int starting) {
        if (snapshot == null) {   //snapshot not retrieved
            return starting;
        }
        if (path == null) {
            throw new IllegalArgumentException("illegal path value");
        }
        String[] pathItems = path.split("/");
        DataSnapshot currentNode = snapshot;
        for (int i = 0; i < pathItems.length; i++) {
            if (currentNode.hasChild(KEY_PERMISSIONS)) {
                starting |= currentNode.child(KEY_PERMISSIONS).getValue(Integer.class);
            }
            if (snapshot.hasChild(pathItems[i])) {
                currentNode = snapshot.child(pathItems[i]);
            } else {  //child doesn't exist
                break;
            }
        }
        return starting;
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

        Iterator<Rule> iterator =  new Iterator<Rule>() {
            @Override
            public boolean hasNext() {
                return nodeStack.size() > 0;
            }

            @Override
            public Rule next() {
                DataSnapshot node = nodeStack.pop();
                Rule inheritedRule = inheritanceStack.pop();

                Rule result = new Rule();
                String key = node.getKey();
                if(!KEY_RULES.equals(key)) {   //key_rules is the root directory
                    if(inheritedRule.path != null){
                        result.path = inheritedRule.path +"/" + key;
                    }else{
                        result.path = key;
                    }
                }else{
                    result.path = null;
                }
                result.permissions = inheritedRule.permissions;
                if (node.hasChild(KEY_PERMISSIONS)) {
                    result.permissions |= node.child(KEY_PERMISSIONS).getValue(Integer.class);
                }
                for (Iterator<DataSnapshot> iterator = node.getChildren().iterator(); iterator.hasNext(); ) {
                    DataSnapshot child = iterator.next();
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

        return iterator;
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
