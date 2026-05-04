/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

/**
 * Transparent activity used by {@code LatinIME} (and any other service-context caller)
 * to request runtime permissions. Services cannot call {@link Activity#requestPermissions}
 * directly, so we launch this no-display activity, it issues the request, dispatches the
 * result back to a registered callback, then finishes.
 *
 * Originally part of upstream HeliBoard, removed in upstream commit "Remove old settings
 * and clean up" — restored here because UP Keyboard's voice/dictation flow needs to be
 * able to request {@code RECORD_AUDIO} when the user taps the dictation button without
 * having granted the permission yet.
 */
public final class PermissionsActivity
        extends Activity implements ActivityCompat.OnRequestPermissionsResultCallback {

    public static final String EXTRA_PERMISSION_REQUESTED_PERMISSIONS = "requested_permissions";
    public static final String EXTRA_PERMISSION_REQUEST_CODE = "request_code";

    private static final int INVALID_REQUEST_CODE = -1;

    /**
     * Process-global registry of pending permission callbacks. Keyed by the request code
     * supplied to {@link #run}; the entry is removed once a result arrives. The map is
     * accessed only on the main thread (Activity + IME callbacks both run there).
     */
    private static final java.util.Map<Integer, ResultCallback> sCallbacks = new java.util.HashMap<>();

    public interface ResultCallback {
        /** @param granted whether *all* requested permissions were granted */
        void onResult(boolean granted, @NonNull String[] permissions, @NonNull int[] grantResults);
    }

    private int mPendingRequestCode = INVALID_REQUEST_CODE;

    /**
     * Starts a {@code PermissionsActivity} that requests the supplied permissions.
     * Pass an optional [callback] to be notified once the user has responded.
     */
    public static void run(@NonNull Context context, int requestCode,
                           @Nullable ResultCallback callback,
                           @NonNull String... permissionStrings) {
        if (callback != null) {
            sCallbacks.put(requestCode, callback);
        }
        Intent intent = new Intent(context.getApplicationContext(), PermissionsActivity.class);
        intent.putExtra(EXTRA_PERMISSION_REQUESTED_PERMISSIONS, permissionStrings);
        intent.putExtra(EXTRA_PERMISSION_REQUEST_CODE, requestCode);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPendingRequestCode = (savedInstanceState != null)
                ? savedInstanceState.getInt(EXTRA_PERMISSION_REQUEST_CODE, INVALID_REQUEST_CODE)
                : INVALID_REQUEST_CODE;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_PERMISSION_REQUEST_CODE, mPendingRequestCode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPendingRequestCode == INVALID_REQUEST_CODE) {
            final Bundle extras = getIntent().getExtras();
            if (extras == null) {
                finish();
                return;
            }
            final String[] permissionsToRequest =
                    extras.getStringArray(EXTRA_PERMISSION_REQUESTED_PERMISSIONS);
            mPendingRequestCode = extras.getInt(EXTRA_PERMISSION_REQUEST_CODE, INVALID_REQUEST_CODE);
            if (permissionsToRequest == null || permissionsToRequest.length == 0
                    || mPendingRequestCode == INVALID_REQUEST_CODE) {
                finish();
                return;
            }
            ActivityCompat.requestPermissions(this, permissionsToRequest, mPendingRequestCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        mPendingRequestCode = INVALID_REQUEST_CODE;
        ResultCallback cb = sCallbacks.remove(requestCode);
        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (cb != null) {
            cb.onResult(allGranted, permissions, grantResults);
        }
        finish();
    }
}
