package info.protonet.imageresizer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.support.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.telecom.Call;
import android.util.Base64;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.camera.FileHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import bolts.Task;

public class ImageResizer extends CordovaPlugin {
    private static final int ARGUMENT_NUMBER = 1;

    private String uri;
    private String folderName;
    private String fileName;
    private int quality;
    private int width;
    private int height;
    private boolean base64 = false;
    private boolean fit = false;

    private static class TaskParams{
        JSONArray args;
        CallbackContext callbackContext;

        TaskParams(JSONArray args, CallbackContext callbackContext){
            this.args = args;
            this.callbackContext = callbackContext;
        }
    }

    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        TaskParams params = new TaskParams(args, callbackContext);
        if (action.equals("resize")) {
            new ResizeTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
            return true;
        } else if(action.equals("rotateFromExif")) {
            new RotateFromExifTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
            return true;
        }
        else {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
            return false;
        }
    }

    class ResizeTask extends AsyncTask<TaskParams, String, String> {
        @Override
        protected String doInBackground(TaskParams[] params) {
            TaskParams currentParams = params[0];
            JSONArray args = currentParams.args;
            CallbackContext callbackContext = currentParams.callbackContext;
            boolean isFileUri = false;

            checkParameters(args, callbackContext);

            try {
                // get the arguments
                JSONObject jsonObject = args.getJSONObject(0);

                uri = jsonObject.getString("uri");

                isFileUri = !uri.startsWith("data");

                int exifRotation = 0;
                if(jsonObject.has("exifRotation") && jsonObject.getBoolean("exifRotation")){
                    exifRotation = getExifRotationFromUri(uri);
                }

                folderName = null;
                if (jsonObject.has("folderName")) {
                    folderName = jsonObject.getString("folderName");
                }
                fileName = null;
                if (jsonObject.has("fileName")) {
                    fileName = jsonObject.getString("fileName");
                }
                quality = jsonObject.optInt("quality", 85);
                width = jsonObject.getInt("width");
                height = jsonObject.getInt("height");
                base64 = jsonObject.optBoolean("base64", false);
                fit = jsonObject.optBoolean("fit", false);

                Bitmap bitmap;
                // load the image from uri
                if (isFileUri) {
                    bitmap = loadScaledBitmapFromUri(uri, width, height);
                } else {
                    bitmap = loadBase64ScaledBitmapFromUri(uri, width, height, fit);
                }

                if(jsonObject.has("exifRotation") && jsonObject.getBoolean("exifRotation")){
                    bitmap = rotateBitmap(bitmap, exifRotation);
                }

                String response;

                // save the image as jpeg on the device
                if (!base64) {
                    Uri scaledFile = saveFile(bitmap);
                    response = scaledFile.toString();
                } else {
                    response = getStringImage(bitmap, quality);
                }

                bitmap = null;

                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, response));

            } catch (JSONException e) {
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
                Log.e("Protonet", "JSON Exception during the Image Resizer Plugin... :(");
            } finally {
                return null;
            }

        }
    }

    class RotateFromExifTask extends AsyncTask<TaskParams, String, String> {
        @Override
        protected String doInBackground(TaskParams[] params) {
            TaskParams currentParams = params[0];
            JSONArray args = currentParams.args;
            CallbackContext callbackContext = currentParams.callbackContext;
            boolean isFileUri = false;

            checkParameters(args, callbackContext);

            try {
                // get the arguments
                JSONObject jsonObject = args.getJSONObject(0);

                uri = jsonObject.getString("uri");

                isFileUri = !uri.startsWith("data");

                int exifRotation = getExifRotationFromUri(uri);

                Bitmap bitmap;
                // load the image from uri
                if (isFileUri) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uri, cordova), null, options);

                    options.inJustDecodeBounds = false;
                    options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, width, height);
                    bitmap = BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uri, cordova), null, options);

                } else {
                    String pureBase64Encoded = uri.substring(uri.indexOf(",") + 1);
                    byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

                    bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                }

                bitmap = rotateBitmap(bitmap, exifRotation);

                String response;

                folderName = null;
                if (jsonObject.has("folderName")) {
                    folderName = jsonObject.getString("folderName");
                }
                fileName = null;
                if (jsonObject.has("fileName")) {
                    fileName = jsonObject.getString("fileName");
                }
                base64 = jsonObject.optBoolean("base64", false);
                quality = jsonObject.optInt("quality", 85);

                if (!base64) {
                    Uri scaledFile = saveFile(bitmap);
                    response = scaledFile.toString();
                } else {
                    response = getStringImage(bitmap, quality);
                }

                bitmap = null;
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, response));

            } catch (JSONException e) {
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
                Log.e("Protonet", "JSON Exception during the Image Resizer Plugin... :(");
            } finally {
                return null;
            }

        }
    }

    public String getStringImage(Bitmap bmp, int quality) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        byte[] imageBytes = baos.toByteArray();

        String encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
        return encodedImage;
    }

    private Bitmap loadBase64ScaledBitmapFromUri(String uriString, int width, int height, boolean fit) {
        try {

            String pureBase64Encoded = uriString.substring(uriString.indexOf(",") + 1);
            byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

            Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            int sourceWidth = decodedBitmap.getWidth();
            int sourceHeight = decodedBitmap.getHeight();

            float ratio = sourceWidth > sourceHeight ? ((float) width / sourceWidth) : ((float) height / sourceHeight);

            int execWidth = width;
            int execHeigth = height;

            if (fit) {
                execWidth = Math.round(ratio * sourceWidth);
                execHeigth = Math.round(ratio * sourceHeight);
            }

            Bitmap scaled = Bitmap.createScaledBitmap(decodedBitmap, execWidth, execHeigth, true);

            decodedBytes = null;
            decodedBitmap = null;

            return scaled;

        } catch (Exception e) {
            Log.e("Protonet", e.toString());
        }
        return null;
    }

    /**
     * Loads a Bitmap of the given android uri path
     *
     * @params uri the URI who points to the image
     **/
    private Bitmap loadScaledBitmapFromUri(String uriString, int width, int height) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uriString, cordova), null, options);

            //calc aspect ratio
            int[] retval = calculateAspectRatio(options.outWidth, options.outHeight);

            options.inJustDecodeBounds = false;
            options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, width, height);
            Bitmap unscaledBitmap = BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uriString, cordova), null, options);
            return Bitmap.createScaledBitmap(unscaledBitmap, retval[0], retval[1], true);
        } catch (FileNotFoundException e) {
            Log.e("Protonet", "File not found. :(");
        } catch (IOException e) {
            Log.e("Protonet", "IO Exception :(");
        } catch (Exception e) {
            Log.e("Protonet", e.toString());
        }
        return null;
    }

    public static int getExifRotationFromUri(String uriString){
        ExifInterface exif = null;
        try {
            if(!uriString.startsWith("data")){
                exif = new ExifInterface(uriString);
            }else {
                String pureBase64Encoded = uriString.substring(uriString.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);
                exif = new ExifInterface(new ByteArrayInputStream(decodedBytes));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED);

        return orientation;
    }

    public static Bitmap rotateBitmap(Bitmap bitmap, int orientation) {

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return bitmap;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                return bitmap;
        }
        try {
            Bitmap bmRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return bmRotated;
        }
        catch (OutOfMemoryError e) {
            e.printStackTrace();
            return null;
        }
    }

    private Uri saveFile(Bitmap bitmap) {
        File folder = null;
        if (folderName == null) {
            folder = new File(this.getTempDirectoryPath());
        } else {
            if (folderName.contains("/")) {
                folder = new File(folderName.replace("file://", ""));
            } else {
                folder = new File(Environment.getExternalStorageDirectory() + "/" + folderName);
            }
        }
        boolean success = true;
        if (!folder.exists()) {
            success = folder.mkdir();
        }

        if (success) {
            if (fileName == null) {
                fileName = System.currentTimeMillis() + ".jpg";
            }
            File file = new File(folder, fileName);
            if (file.exists()) file.delete();
            try {
                FileOutputStream out = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
                out.flush();
                out.close();
            } catch (Exception e) {
                Log.e("Protonet", e.toString());
            }
            return Uri.fromFile(file);
        }
        return null;
    }

    /**
     * Figure out what ratio we can load our image into memory at while still being bigger than
     * our desired width and height
     *
     * @param srcWidth
     * @param srcHeight
     * @param dstWidth
     * @param dstHeight
     * @return
     */
    private int calculateSampleSize(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        final float srcAspect = (float) srcWidth / (float) srcHeight;
        final float dstAspect = (float) dstWidth / (float) dstHeight;

        if (srcAspect > dstAspect) {
            return srcWidth / dstWidth;
        } else {
            return srcHeight / dstHeight;
        }
    }

    /**
     * Maintain the aspect ratio so the resulting image does not look smooshed
     *
     * @param origWidth
     * @param origHeight
     * @return
     */
    private int[] calculateAspectRatio(int origWidth, int origHeight) {
        int newWidth = width;
        int newHeight = height;

        // If no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0) {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // Only the width was specified
        else if (newWidth > 0 && newHeight <= 0) {
            newHeight = (newWidth * origHeight) / origWidth;
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0) {
            newWidth = (newHeight * origWidth) / origHeight;
        }
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio) {
                newHeight = (newWidth * origHeight) / origWidth;
            } else if (origRatio < newRatio) {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] retval = new int[2];
        retval[0] = newWidth;
        retval[1] = newHeight;
        return retval;
    }

    private boolean checkParameters(JSONArray args, CallbackContext callbackContext) {
        if (args.length() != ARGUMENT_NUMBER) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION));
            return false;
        }
        return true;
    }

    private String getTempDirectoryPath() {
        File cache = null;

        // SD Card Mounted
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            cache = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
                    "/Android/data/" + cordova.getActivity().getPackageName() + "/cache/");
        } else {
            // Use internal storage
            cache = cordova.getActivity().getCacheDir();
        }

        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }
}
