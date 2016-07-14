package examples.baku.io.permissions;

import android.app.Notification;

import com.google.firebase.database.ServerValue;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.Permission;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by phamilton on 6/28/16.
 */

//TODO: multiple resources (Request groups)
public class PermissionRequest {

    public static final String EXTRA_TITLE = "title";

    private String id;
    private String source;
    private Map<String, Integer> permissions = new HashMap<>();
    private Map<String, String> description = new HashMap<>();
    private long timeStamp;

    public PermissionRequest() {
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Map<String, Integer> getPermissions() {
        Map<String, Integer> escapedPermissions = new HashMap<>();
        try {
            for (String path : permissions.keySet()) {
                String escapedPath = URLEncoder.encode(path, "UTF-8");
                escapedPermissions.put(escapedPath, permissions.get(path));
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("invalid permission path");
        }
        return escapedPermissions;
    }

    //Path must be encoded so as not to use illegal key characters in firebase
    public void setPermissions(Map<String, Integer> permissions) {
        Map<String, Integer> unescapedPermissions = new HashMap<>();
        try {
            for (String path : permissions.keySet()) {
                String escapedPath = URLDecoder.decode(path, "UTF-8");
                unescapedPermissions.put(escapedPath, permissions.get(path));
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("invalid permission path");
        }
        this.permissions = unescapedPermissions;
    }

    public Map<String, String> getDescription() {
        return description;
    }

    public void setDescription(Map<String, String> description) {
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }


    public Map<String,String> getTimeStamp() {
        return ServerValue.TIMESTAMP;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public void grantAll(PermissionManager manager) {
        Blessing blessing = manager.bless(getSource());

        //accept all suggested permissions
        for (String permissionPath : permissions.keySet()) {
            blessing.setPermissions(permissionPath, permissions.get(permissionPath));
        }
    }

    public void finish(PermissionManager manager){
        manager.finishRequest(id);
    }

    public static class Builder {
        private PermissionRequest request;

        public Builder() {
            this.request = new PermissionRequest();
        }

        public PermissionRequest.Builder addPermission(String path, int suggested) {
            request.permissions.put(path, suggested);
            return this;
        }

        public PermissionRequest build() {
            //TODO: check valid
            return request;
        }

    }

}
