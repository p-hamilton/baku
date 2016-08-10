// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions.examples;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.TextInputLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.MaterialIcons;
import com.onegravity.rteditor.RTManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.UUID;

import examples.baku.io.permissions.Blessing;
import examples.baku.io.permissions.PermissionManager;
import examples.baku.io.permissions.PermissionRequest;
import examples.baku.io.permissions.PermissionService;
import examples.baku.io.permissions.PermissionedText;
import examples.baku.io.permissions.R;
import examples.baku.io.permissions.discovery.DevicePickerActivity;
import examples.baku.io.permissions.synchronization.SyncText;
import examples.baku.io.permissions.synchronization.SyncTextDiff;

public class ComposeActivity extends AppCompatActivity implements ServiceConnection {

    public final static String EXTRA_MESSAGE_ID = "messageId";
    public final static String EXTRA_MESSAGE_PATH = "messagePath";

    private String mPath;

    private String mOwner;
    private String mDeviceId;
    private String mId;
    private PermissionService mPermissionService;
    private PermissionManager mPermissionManager;
    private DatabaseReference mMessageRef;
    private DatabaseReference mSyncedMessageRef;

    private Blessing mCastBlessing;
    private Blessing mPublicBlessing;

    PermissionedText mTo;
    PermissionedText mFrom;
    PermissionedText mSubject;
    PermissionedText mMessage;

    Multimap<String, PermissionRequest> mRequests = HashMultimap.create();
    HashMap<String, PermissionedText> mPermissionedFields = new HashMap<>();
    HashMap<String, Integer> mPermissions = new HashMap<>();
    HashMap<String, SyncText> syncTexts = new HashMap<>();

    RTManager mRTManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compose);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle("Compose");
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        toolbar.setTitle("Compose Message");


        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage();
            }
        });

        mTo = (PermissionedText) findViewById(R.id.composeTo);

        mFrom = (PermissionedText) findViewById(R.id.composeFrom);

        mSubject = (PermissionedText) findViewById(R.id.composeSubject);

        mMessage = (PermissionedText) findViewById(R.id.composeMessage);

        bindService(new Intent(this, PermissionService.class), this, Service.BIND_AUTO_CREATE);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_compose, menu);
        menu.findItem(R.id.action_cast).setIcon(
                new IconDrawable(this, MaterialIcons.md_cast)
                        .color(Color.WHITE)
                        .actionBarSize());
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_send) {
            sendMessage();
        } else if (id == R.id.action_cast) {
            if (mPermissionService != null) {
                Intent requestIntent = new Intent(ComposeActivity.this, DevicePickerActivity.class);
                requestIntent.putExtra(DevicePickerActivity.EXTRA_REQUEST, DevicePickerActivity.REQUEST_DEVICE_ID);
                requestIntent.putExtra(DevicePickerActivity.EXTRA_REQUEST_ARGS, mPath);
                startActivityForResult(requestIntent, DevicePickerActivity.REQUEST_DEVICE_ID);
            }

        } else if (id == R.id.action_settings) {

        } else if (id == android.R.id.home) {
            finish();
        }

        return super.onOptionsItemSelected(item);
    }

    void sendMessage() {
        //TODO: PermissionManager.request()
        mPermissionManager.request(mPath + "/send", mDeviceId)
                .putExtra(PermissionManager.EXTRA_TIMEOUT, "2000")
                .putExtra(PermissionManager.EXTRA_COLOR, "#F00");
        finish();
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DevicePickerActivity.REQUEST_DEVICE_ID && data != null && data.hasExtra(DevicePickerActivity.EXTRA_DEVICE_ID)) {
            String targetDevice = data.getStringExtra(DevicePickerActivity.EXTRA_DEVICE_ID);

            if (!mOwner.equals(targetDevice)) {
                //find most appropriate blessing to extend from
                mCastBlessing = mPermissionManager.getBlessing(mOwner, mDeviceId);
                if (mCastBlessing == null) {
                    mCastBlessing = mPermissionManager.getRootBlessing();
                }
                mCastBlessing.bless(targetDevice)
                        .setPermissions(mPath, PermissionManager.FLAG_READ)
                        .setPermissions(mPath + "/message", PermissionManager.FLAG_SUGGEST)
                        .setPermissions(mPath + "/subject", PermissionManager.FLAG_SUGGEST);
            }

            JSONObject castArgs = new JSONObject();
            try {
                castArgs.put("activity", ComposeActivity.class.getSimpleName());
                castArgs.put(EXTRA_MESSAGE_PATH, mPath);
                mPermissionService.getMessenger().to(targetDevice).emit("cast", castArgs.toString());

                mPermissionService.updateConstellationDevice(targetDevice);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }


    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        PermissionService.PermissionServiceBinder binder = (PermissionService.PermissionServiceBinder) service;
        mPermissionService = binder.getInstance();


        if (mPermissionService != null) {
            mPermissionManager = mPermissionService.getPermissionManager();
            mDeviceId = mPermissionService.getDeviceId();

            Intent intent = getIntent();
            if (intent != null) {
                if (intent.hasExtra(EXTRA_MESSAGE_PATH)) {
                    mPath = intent.getStringExtra(EXTRA_MESSAGE_PATH);
                    String[] pathElements = mPath.split("/");
                    mId = pathElements[pathElements.length - 1];
                } else if (intent.hasExtra(EXTRA_MESSAGE_ID)) {
                    mId = intent.getStringExtra(EXTRA_MESSAGE_ID);
                    mPath = EmailActivity.KEY_DOCUMENTS
                            + "/" + mDeviceId
                            + "/" + EmailActivity.KEY_EMAILS
                            + "/" + EmailActivity.KEY_MESSAGES
                            + "/" + mId;
                }
            }

            if (mPath == null) {
                mId = UUID.randomUUID().toString();
                mPath = "documents/" + mDeviceId + "/emails/messages/" + mId;
            }

            //parse path to get owner
            String[] pathElements = mPath.split("/");
            if (pathElements != null && pathElements.length > 1) {
                mOwner = pathElements[1];
            }

            mMessageRef = mPermissionService.getFirebaseDB().getReference(mPath);
            mSyncedMessageRef = mPermissionService.getFirebaseDB().getReference("documents/" + mOwner + "/emails/syncedMessages/"+mId);
            mPermissionManager.addPermissionEventListener(mPath, messagePermissionListener);
            wrapTextField(mTo, "to");
            wrapTextField(mFrom, "from");
            wrapTextField(mSubject, "subject");
            wrapTextField(mMessage, "message");

            mPublicBlessing = mPermissionManager.bless("public")
                    .setPermissions(mPath + "/subject", PermissionManager.FLAG_READ);

            mPermissionManager.addOnRequestListener("documents/" + mDeviceId + "/emails/messages/" + mId + "/*", new PermissionManager.OnRequestListener() {
                @Override
                public boolean onRequest(PermissionRequest request, Blessing blessing) {
                    mRequests.put(request.getPath(), request);
                    return true;
                }

                @Override
                public void onRequestRemoved(PermissionRequest request, Blessing blessing) {

                }
            });

            mPermissionService.setStatus(EXTRA_MESSAGE_PATH, mPath);

        }
    }

    PermissionManager.OnPermissionChangeListener messagePermissionListener = new PermissionManager.OnPermissionChangeListener() {
        @Override
        public void onPermissionChange(int current) {
            if (current > 0) {
                mMessageRef.child("id").setValue(mId);
            }
        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };


    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    void updateTextField(final String key) {
        String path = "documents/" + mDeviceId + "/emails/messages/" + mId + "/" + key;
        Integer current = mPermissions.get(path);
        if (current == null)
            current = 0;

        PermissionedText edit = mPermissionedFields.get(key);
        edit.setPermissions(current);

        if((current & PermissionManager.FLAG_WRITE) != PermissionManager.FLAG_WRITE &&(current & PermissionManager.FLAG_SUGGEST) != PermissionManager.FLAG_SUGGEST){
            edit.rejectSuggestions(mDeviceId);
        }

        if ((current & PermissionManager.FLAG_WRITE) == PermissionManager.FLAG_WRITE) {
            edit.setEnabled(true);
            edit.setFocusable(true);
            edit.setBackgroundColor(Color.TRANSPARENT);
        } else if ((current & PermissionManager.FLAG_READ) == PermissionManager.FLAG_READ) {
            edit.setEnabled(false);
//            editContainer.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View v) {
//                    mPermissionManager.request(mPath + "/" + key, mDeviceId + mId)
//                            .setPermissions(PermissionManager.FLAG_WRITE)
//                            .udpate();
//                }
//            });
            edit.setFocusable(true);
            edit.setBackgroundColor(Color.TRANSPARENT);
        } else {
            edit.setEnabled(false);
            edit.setFocusable(false);
            edit.setBackgroundColor(Color.BLACK);
        }
    }

    private PermissionedText.OnSuggestionSelectedListener suggestionSelectedListener = new PermissionedText.OnSuggestionSelectedListener() {
        @Override
        public void onSelected(SyncTextDiff diff) {
            Toast.makeText(getApplicationContext(), diff.text, 0).show();
        }
    };

    void wrapTextField(final PermissionedText edit, final String key) {
        edit.setSyncText(new SyncText(mDeviceId, PermissionManager.FLAG_SUGGEST, mSyncedMessageRef.child(key), mMessageRef.child(key)));
        edit.setOnSuggestionSelectedListener(suggestionSelectedListener);
        mPermissionedFields.put(key, edit);
        final String path = "documents/" + mDeviceId + "/emails/messages/" + mId + "/" + key;

        mPermissionManager.addPermissionEventListener(mPath + "/" + key, new PermissionManager.OnPermissionChangeListener() {
            @Override
            public void onPermissionChange(int current) {
                mPermissions.put(path, current);
                updateTextField(key);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    public void unlink(){
        for(PermissionedText text : mPermissionedFields.values()){
            text.unlink();
        }
        if (mPermissionService != null) {
            if (mPublicBlessing != null) {
                mPublicBlessing.revokePermissions(mPath);
            }

            //cancel all requests made from this activity
            mPermissionManager.cancelRequests(mDeviceId + mId);
        }
        unbindService(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unlink();
    }
}
