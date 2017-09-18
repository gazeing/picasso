/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.picasso;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import static com.squareup.picasso.Picasso.LoadedFrom.DISK;

/**
 * Based on Picasso's ResourceRequestHandler however this one can handle arbitrary drawable types,
 * not just bitmaps. Namely VectorDrawables.
 * <p>
 * Usage is a bit awkward as the default ResourceRequestHandler gets priority.
 * Instead of using a request for R.drawable.vector or android.resource://com.example/123
 * One must use alt.android.resource://com.example/123
 * <p>
 * There are two helper methods to generate these Uris
 * {@link #checkAndUpdateScheme(Uri)}
 * {@link #uriFromResource(Context, int)}
 */
public class VectorResourceRequestHandler extends RequestHandler {
    public static final String SCHEME_ALT_ANDROID_RESOURCE = "alt.android.resource";
    private static final Bitmap.Config DEFAULT_CONFIG = Bitmap.Config.ARGB_8888;
    private final Context context;

    public VectorResourceRequestHandler(Context context) {
        this.context = context;
    }

    @Override
    public boolean canHandleRequest(Request data) {
        if (data.resourceId != 0) {
            return true;
        }
        String scheme = data.uri.getScheme();
        return SCHEME_ALT_ANDROID_RESOURCE.equals(scheme) || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme);
    }

    /**
     * Checks for SCHEME_ANDROID_RESOURCE URIs and replaces them with SCHEME_ALT_ANDROID_RESOURCE
     *
     * @param uri Original uri to check and possibly update
     * @return Either the original Uri or a newly modified one
     */
    public static Uri checkAndUpdateScheme(Uri uri) {
        if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
            return uri.buildUpon().scheme(SCHEME_ALT_ANDROID_RESOURCE).build();
        }
        return uri;
    }

    /**
     * Create a SCHEME_ALT_ANDROID_RESOURCE Uri from a resource id
     */
    public static Uri uriFromResource(Context context, int resourceId) {
        return new Uri.Builder().scheme(SCHEME_ALT_ANDROID_RESOURCE)
                .authority(context.getPackageName())
                .path(Integer.toString(resourceId)).build();
    }

    @Override
    public Result load(Request request, int networkPolicy) throws IOException {
        Resources res = getResources(context, request);
        int id = getResourceId(res, request);
        return new Result(decodeResource(res, id, request), DISK);
    }

    private static Bitmap decodeResource(Resources resources, int id, Request data) {
        final BitmapFactory.Options options = createBitmapOptions(data);
        if (requiresInSampleSize(options)) {
            BitmapFactory.decodeResource(resources, id, options);
            calculateInSampleSize(data.targetWidth, data.targetHeight, options, data);
        }
        Bitmap bmp = BitmapFactory.decodeResource(resources, id, options);
        if (bmp == null) {
            Drawable d = resources.getDrawable(id);
            int intrinsicWidth = d.getIntrinsicWidth();
            int intrinsicHeight = d.getIntrinsicHeight();
            int width, height;
            if (data.targetWidth > 0 && (!data.onlyScaleDown || data.targetWidth < intrinsicWidth)) {
                width = data.targetWidth;
            } else {
                width = intrinsicWidth;
            }
            if (data.targetHeight > 0 && (!data.onlyScaleDown || data.targetHeight < intrinsicHeight)) {
                height = data.targetHeight;
            } else {
                height = intrinsicHeight;
            }
            if (height == 0 || width == 0) {
                return null;
            }
            bmp = Bitmap.createBitmap(width, height, data.config != null ? data.config : DEFAULT_CONFIG);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            if (width != intrinsicWidth || height != intrinsicHeight) {
                c.scale(width / (float) intrinsicWidth, height / (float) intrinsicHeight);
            }
            d.draw(c);
            c.setBitmap(null);
        }
        return bmp;
    }

    /**
     * Lazily create {@link BitmapFactory.Options} based in given
     * {@link Request}, only instantiating them if needed.
     */
    static BitmapFactory.Options createBitmapOptions(Request data) {
        final boolean justBounds = data.hasSize();
        final boolean hasConfig = data.config != null;
        BitmapFactory.Options options = null;
        if (justBounds || hasConfig) { // || data.purgeable) {
            options = new BitmapFactory.Options();
            options.inJustDecodeBounds = justBounds;
//      options.inInputShareable = data.purgeable;
//      options.inPurgeable = data.purgeable;
            if (hasConfig) {
                options.inPreferredConfig = data.config;
            }
        }
        return options;
    }

    static boolean requiresInSampleSize(BitmapFactory.Options options) {
        return options != null && options.inJustDecodeBounds;
    }

    static void calculateInSampleSize(int reqWidth, int reqHeight, BitmapFactory.Options options,
                                      Request request) {
        calculateInSampleSize(reqWidth, reqHeight, options.outWidth, options.outHeight, options,
                request);
    }

    static void calculateInSampleSize(int reqWidth, int reqHeight, int width, int height,
                                      BitmapFactory.Options options, Request request) {
        int sampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int heightRatio;
            final int widthRatio;
            if (reqHeight == 0) {
                sampleSize = (int) Math.floor((float) width / (float) reqWidth);
            } else if (reqWidth == 0) {
                sampleSize = (int) Math.floor((float) height / (float) reqHeight);
            } else {
                heightRatio = (int) Math.floor((float) height / (float) reqHeight);
                widthRatio = (int) Math.floor((float) width / (float) reqWidth);
                sampleSize = request.centerInside
                        ? Math.max(heightRatio, widthRatio)
                        : Math.min(heightRatio, widthRatio);
            }
        }
        options.inSampleSize = sampleSize;
        options.inJustDecodeBounds = false;
    }

    static int getResourceId(Resources resources, Request data) throws FileNotFoundException {
        if (data.resourceId != 0 || data.uri == null) {
            return data.resourceId;
        }
        String pkg = data.uri.getAuthority();
        if (pkg == null) {
            throw new FileNotFoundException("No package provided: " + data.uri);
        }
        int id;
        List<String> segments = data.uri.getPathSegments();
        if (segments == null || segments.isEmpty()) {
            throw new FileNotFoundException("No path segments: " + data.uri);
        } else if (segments.size() == 1) {
            try {
                id = Integer.parseInt(segments.get(0));
            } catch (NumberFormatException e) {
                throw new FileNotFoundException("Last path segment is not a resource ID: " + data.uri);
            }
        } else if (segments.size() == 2) {
            String type = segments.get(0);
            String name = segments.get(1);
            id = resources.getIdentifier(name, type, pkg);
        } else {
            throw new FileNotFoundException("More than two path segments: " + data.uri);
        }
        return id;
    }

    static Resources getResources(Context context, Request data) throws FileNotFoundException {
        if (data.resourceId != 0 || data.uri == null) {
            return context.getResources();
        }
        String pkg = data.uri.getAuthority();
        if (pkg == null) {
            throw new FileNotFoundException("No package provided: " + data.uri);
        }
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getResourcesForApplication(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new FileNotFoundException("Unable to obtain resources for package: " + data.uri);
        }
    }
}