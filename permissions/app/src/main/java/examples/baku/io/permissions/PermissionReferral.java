// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions;

import com.google.firebase.database.ServerValue;

import java.util.Map;

/**
 * Created by phamilton on 7/6/16.
 */
public class PermissionReferral {
    private String id;
    private String source;
    private String resource;
    private int flags;
    private long timeStamp;

    public PermissionReferral() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public Map<String,String> getTimeStamp() {
        return ServerValue.TIMESTAMP;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }
}
