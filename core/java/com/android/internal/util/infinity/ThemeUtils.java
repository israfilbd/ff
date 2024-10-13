/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.infinity;

import android.util.PathParser;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ThemeUtils {

    public static final String TAG = "ThemeUtils";

    public static final String FONT_KEY = "android.theme.customization.font";
    public static final String ICON_SHAPE_KEY = "android.theme.customization.adaptive_icon_shape";

    public static final Comparator<OverlayInfo> OVERLAY_INFO_COMPARATOR =
            Comparator.comparingInt(a -> a.priority);

    private WeakReference<Context> mContextRef;
    private IOverlayManager mOverlayManager;
    private PackageManager pm;

    private Resources systemResources;

    public ThemeUtils(Context context) {
        mContextRef = new WeakReference<>(context);
        mOverlayManager = IOverlayManager.Stub.asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        pm = context.getPackageManager();
        systemResources = Resources.getSystem();
    }

    public void setOverlayEnabled(String category, String packageName, String target) {
        final String currentPackageName = getOverlayInfos(category, target).stream()
                .filter(info -> info.isEnabled())
                .map(info -> info.packageName)
                .findFirst()
                .orElse(null);

        try {
            if (target.equals(packageName)) {
                mOverlayManager.setEnabled(currentPackageName, false, UserHandle.USER_SYSTEM);
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName, UserHandle.USER_SYSTEM);
            }

            writeSettings(category, packageName, target.equals(packageName));

        } catch (RemoteException e) {
            Log.e(TAG, "Failed to enable overlay.", e);
        }
    }

    public void writeSettings(String category, String packageName, boolean disable) {
        Context context = mContextRef.get();
        if (context == null) return;

        final String overlayPackageJson = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, UserHandle.USER_CURRENT);
        JSONObject object;
        try {
            if (overlayPackageJson == null) {
                object = new JSONObject();
            } else {
                object = new JSONObject(overlayPackageJson);
            }
            if (disable) {
                if (object.has(category)) object.remove(category);
            } else {
                object.put(category, packageName);
            }
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    object.toString(), UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
    }

    public List<String> getOverlayPackagesForCategory(String category) {
        return getOverlayPackagesForCategory(category, "android");
    }

    public List<String> getOverlayPackagesForCategory(String category, String target) {
        List<String> overlays = new ArrayList<>();
        List<String> mPkgs = new ArrayList<>();
        overlays.add(target);
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                mPkgs.add(info.getPackageName());
            }
        }
        Collections.sort(mPkgs);
        overlays.addAll(mPkgs);
        return overlays;
    }

    public List<OverlayInfo> getOverlayInfos(String category) {
        return getOverlayInfos(category, "android");
    }

    public List<OverlayInfo> getOverlayInfos(String category, String target) {
        List<OverlayInfo> filteredInfos = new ArrayList<>();
        try {
            List<OverlayInfo> overlayInfos = mOverlayManager.getOverlayInfosForTarget(target, UserHandle.USER_SYSTEM);
            for (OverlayInfo overlayInfo : overlayInfos) {
                if (category.equals(overlayInfo.category)) {
                    filteredInfos.add(overlayInfo);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get overlay infos.", e);
        }
        filteredInfos.sort(OVERLAY_INFO_COMPARATOR);
        return filteredInfos;
    }

    public List<Typeface> getFonts() {
        List<Typeface> fontList = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(FONT_KEY)) {
            Resources overlayRes;
            try {
                overlayRes = overlayPackage.equals("android") ? systemResources : pm.getResourcesForApplication(overlayPackage);
                String font = overlayRes.getString(overlayRes.getIdentifier("config_bodyFontFamily", "string", overlayPackage));
                fontList.add(Typeface.create(font, Typeface.NORMAL));
            } catch (NameNotFoundException | Resources.NotFoundException e) {
                // Handle the exception
            }
        }
        return fontList;
    }

    public List<ShapeDrawable> getShapeDrawables() {
        List<ShapeDrawable> shapeList = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(ICON_SHAPE_KEY)) {
            shapeList.add(createShapeDrawable(overlayPackage));
        }
        return shapeList;
    }

    public ShapeDrawable createShapeDrawable(String overlayPackage) {
        Resources overlayRes;
        try {
            overlayRes = overlayPackage.equals("android") ? systemResources : pm.getResourcesForApplication(overlayPackage);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Overlay package not found: " + overlayPackage, e);
            return null;
        }

        String shape = overlayRes.getString(overlayRes.getIdentifier("config_icon_mask", "string", overlayPackage));
        Path path = TextUtils.isEmpty(shape) ? null : PathParser.createPathFromPathData(shape);
        PathShape pathShape = new PathShape(path, 100f, 100f);
        ShapeDrawable shapeDrawable = new ShapeDrawable(pathShape);
        int thumbSize = (int) (mContextRef.get().getResources().getDisplayMetrics().density * 72);
        shapeDrawable.setIntrinsicHeight(thumbSize);
        shapeDrawable.setIntrinsicWidth(thumbSize);
        return shapeDrawable;
    }

    public boolean isOverlayEnabled(String overlayPackage) {
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, UserHandle.USER_SYSTEM);
            return info != null && info.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to check if overlay is enabled.", e);
        }
        return false;
    }

    public boolean isDefaultOverlay(String category) {
        for (String overlayPackage : getOverlayPackagesForCategory(category)) {
            try {
                OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, UserHandle.USER_SYSTEM);
                if (info != null && info.isEnabled()) {
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to check if default overlay.", e);
            }
        }
        return true;
    }

    public static String getCurrentClockFontOverlay() {
        IOverlayManager overlayManager = IOverlayManager.Stub.asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        for (String overlayPackage : getFontOverlayPackages()) {
            try {
                OverlayInfo info = overlayManager.getOverlayInfo(overlayPackage, UserHandle.USER_SYSTEM);
                if (info != null && info.isEnabled()) {
                    return overlayPackage;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to get clock font overlay.", e);
            }
        }
        return null;
    }

    public static List<String> getFontOverlayPackages() {
        return new ArrayList<>();
    }
}
