package com.ai.assistance.shower;

import com.ai.assistance.shower.device.DisplayInfo;
import com.ai.assistance.shower.device.Size;
import com.ai.assistance.shower.wrappers.ServiceManager;
import com.ai.assistance.shower.wrappers.SurfaceControl;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

final class DisplayCapture {

    private static final int IMAGE_WAIT_TIMEOUT_MS = 1000;
    private static final int IMAGE_WAIT_INTERVAL_MS = 20;
    /**
     * The picture goes back in one binder reply, and the client's binder buffer is about 1 MB shared
     * by every call in flight (the chat card's live preview and the AI's look can overlap). A full
     * screen PNG is often 2 MB or more, so a larger one is sent as JPEG at the same size instead,
     * keeping coordinates in screen pixels.
     */
    private static final int MAX_REPLY_BYTES = 450 * 1024;
    private static final int[] JPEG_QUALITIES = {90, 80, 70, 60, 50, 40, 30, 20};

    private DisplayCapture() {
    }

    /** For the binder reply: PNG, or JPEG small enough for one reply. */
    static byte[] captureDisplay(int displayId) {
        return captureDisplay(displayId, 0, MAX_REPLY_BYTES);
    }

    /**
     * [jpegQuality] 1..100 encodes JPEG at that quality, otherwise PNG. [maxBytes] above 0 shrinks
     * the quality to fit (the binder reply); 0 sends it whole (the pipe).
     */
    static byte[] captureDisplay(int displayId, int jpegQuality, int maxBytes) {
        ImageReader imageReader = null;
        Surface surface = null;
        VirtualDisplay virtualDisplay = null;
        IBinder display = null;
        Image image = null;

        try {
            DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
            if (displayInfo == null) {
                Main.logToFile("DisplayCapture: display not found: " + displayId, null);
                return null;
            }

            Size inputSize = displayInfo.getSize();
            imageReader = ImageReader.newInstance(inputSize.getWidth(), inputSize.getHeight(), android.graphics.PixelFormat.RGBA_8888, 2);
            surface = imageReader.getSurface();

            try {
                virtualDisplay = ServiceManager.getDisplayManager()
                        .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
                Main.logToFile("DisplayCapture: using DisplayManager API for displayId=" + displayId, null);
            } catch (Exception displayManagerException) {
                try {
                    display = createDisplay();
                    setDisplaySurface(display, surface, inputSize.toRect(), inputSize.toRect(), displayInfo.getLayerStack());
                    Main.logToFile("DisplayCapture: using SurfaceControl API for displayId=" + displayId, null);
                } catch (Exception surfaceControlException) {
                    Main.logToFile("DisplayCapture: DisplayManager API failed", displayManagerException);
                    Main.logToFile("DisplayCapture: SurfaceControl API failed", surfaceControlException);
                    throw new AssertionError("Could not create display");
                }
            }

            image = waitForImage(imageReader);
            if (image == null) {
                Main.logToFile("DisplayCapture: timed out waiting for image on display " + displayId, null);
                return null;
            }

            return encodeImage(image, jpegQuality, maxBytes);
        } catch (Throwable t) {
            Main.logToFile("DisplayCapture failed for display " + displayId + ": " + t.getMessage(), t);
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
            if (display != null) {
                try {
                    SurfaceControl.destroyDisplay(display);
                } catch (Throwable t) {
                    Main.logToFile("DisplayCapture: destroyDisplay failed: " + t.getMessage(), t);
                }
            }
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (Throwable t) {
                    Main.logToFile("DisplayCapture: virtualDisplay.release failed: " + t.getMessage(), t);
                }
            }
            if (imageReader != null) {
                try {
                    imageReader.close();
                } catch (Throwable t) {
                    Main.logToFile("DisplayCapture: imageReader.close failed: " + t.getMessage(), t);
                }
            }
        }
    }

    private static IBinder createDisplay() throws Exception {
        boolean secure = Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11
                || (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11 && !"S".equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static Image waitForImage(ImageReader imageReader) throws InterruptedException {
        int waitedMs = 0;
        while (waitedMs < IMAGE_WAIT_TIMEOUT_MS) {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                return image;
            }
            Thread.sleep(IMAGE_WAIT_INTERVAL_MS);
            waitedMs += IMAGE_WAIT_INTERVAL_MS;
        }
        return null;
    }

    private static byte[] encodeImage(Image image, int jpegQuality, int maxBytes) throws IOException {
        Bitmap bitmap = null;
        Bitmap croppedBitmap = null;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            bitmap.copyPixelsFromBuffer(buffer);

            croppedBitmap = rowPadding == 0 ? bitmap : Bitmap.createBitmap(bitmap, 0, 0, width, height);
            if (jpegQuality >= 1 && jpegQuality <= 100) {
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream);
            } else {
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            }
            if (maxBytes <= 0 || outputStream.size() <= maxBytes) {
                return outputStream.toByteArray();
            }
            for (int quality : JPEG_QUALITIES) {
                outputStream.reset();
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
                if (outputStream.size() <= maxBytes) {
                    break;
                }
            }
            if (outputStream.size() > maxBytes) {
                // Too big for one reply even at low quality: fail plainly instead of breaking the binder.
                Main.logToFile("DisplayCapture: picture too large to send, " + outputStream.size() + " bytes", null);
                return null;
            }
            Main.logToFile("DisplayCapture: sent as JPEG, " + outputStream.size() + " bytes", null);
            return outputStream.toByteArray();
        } finally {
            if (croppedBitmap != null && croppedBitmap != bitmap) {
                croppedBitmap.recycle();
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
            outputStream.close();
        }
    }
}
