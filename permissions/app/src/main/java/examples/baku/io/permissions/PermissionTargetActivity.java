//  Copyright 2016 The Vanadium Authors. All rights reserved.
//  Use of this source code is governed by a BSD-style
//  license that can be found in the LICENSE file.

package examples.baku.io.permissions;

import android.app.Activity;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

public class PermissionTargetActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e("zzzzzzzzzz", "GOTCHA!!!!!!!!");
        Toast.makeText(this, "LALA", 0).show();
        finish();
    }
}
