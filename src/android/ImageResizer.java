package com.cordova.imageresizer;

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

public class ImageResizer extends CordovaPlugin {
  private static final int ARGUMENT_NUMBER = 1;
  private boolean switchWidthAndHeightExifs = false;

  private static class TaskParams {
    JSONArray args;
    CallbackContext callbackContext;

    TaskParams(JSONArray args, CallbackContext callbackContext) {
      this.args = args;
      this.callbackContext = callbackContext;
    }
  }

  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    TaskParams params = new TaskParams(args, callbackContext);
    if (action.equals("resize")) {
      new ResizeTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
      return true;
    } else {
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
      checkParameters(args, callbackContext);

      try {
        // get the arguments
        JSONObject jsonObject = args.getJSONObject(0);

        String uri = jsonObject.getString("uri");

        ExifInterface originalExif = getExifInterface(uri);
        switchWidthAndHeightExifs = false;
        int exifRotation = 0;
        if (jsonObject.optBoolean("exifRotation", false)) {
          exifRotation = originalExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        }

        String folderName = jsonObject.optString("folderName", null);
        String fileName = jsonObject.optString("fileName", null);
        int quality = jsonObject.optInt("quality", 85);
        int width = jsonObject.getInt("width");
        int height = jsonObject.getInt("height");
        boolean base64 = jsonObject.optBoolean("base64", false);

        // load the image from uri

        Bitmap bitmap = loadResizedBitmapFromUri(uri, width, height);

        if (jsonObject.has("exifRotation") && jsonObject.getBoolean("exifRotation")) {
          bitmap = rotateBitmap(bitmap, exifRotation);
        }

        String response;

        // save the image as jpeg on the device
        if (!base64) {
          Uri scaledFile = saveFile(bitmap, folderName, fileName, quality);
          copyExif(originalExif, scaledFile.toString().replace("file://", ""), width, height);
          response = scaledFile.toString();
        } else {
          response = getStringImage(bitmap, quality);
        }

        bitmap.recycle();

        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, response));

      } catch (JSONException e) {
        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR,
            "JSON Exception during ResizeTask : " + e.getMessage()));
        Log.e("Protonet", "JSON Exception during ResizeTask.");
      } catch (IOException e) {
        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR,
            "IOException during ResizeTask : " + e.getMessage()));
        Log.e("Protonet", "IOException during ResizeTask.");
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

  private Bitmap loadResizedBitmapFromUri(String uri, int width, int height) throws IOException {

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    decodeUri(uri, options);

    // calc aspect ratio
    int[] retval = calculateAspectRatio(options.outWidth, options.outHeight, width, height);

    options.inSampleSize = calculateInSampleSize(options, width, height);
    options.inJustDecodeBounds = false;
    Bitmap unscaledBitmap = decodeUri(uri, options);
    if (retval[0] == width && retval[1] == height) {
      return unscaledBitmap;
    }
    Bitmap scaledBitmap = Bitmap.createScaledBitmap(unscaledBitmap, retval[0], retval[1], true);
    unscaledBitmap.recycle();
    return scaledBitmap;
  }

  private Bitmap decodeUri(String uri, BitmapFactory.Options options) throws IOException {
    Boolean isBase64 = uri.startsWith("data");
    if (isBase64) {
      String pureBase64Encoded = uri.substring(uri.indexOf(",") + 1);
      byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);
      return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length, options);
    } else {
      return BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uri, cordova), null, options);
    }
  }

  public static ExifInterface getExifInterface(String uriString) throws IOException {
    ExifInterface exif = null;

    if (!uriString.startsWith("data")) {
      exif = new ExifInterface(uriString);
    } else {
      String pureBase64Encoded = uriString.substring(uriString.indexOf(",") + 1);
      byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);
      exif = new ExifInterface(new ByteArrayInputStream(decodedBytes));
    }
    return exif;
  }

  public Bitmap rotateBitmap(Bitmap bitmap, int orientation) {

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
        switchWidthAndHeightExifs = true;
        matrix.setRotate(90);
        matrix.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_90:
        switchWidthAndHeightExifs = true;
        matrix.setRotate(90);
        break;
      case ExifInterface.ORIENTATION_TRANSVERSE:
        switchWidthAndHeightExifs = true;
        matrix.setRotate(-90);
        matrix.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_270:
        switchWidthAndHeightExifs = true;
        matrix.setRotate(-90);
        break;
      default:
        return bitmap;
    }

    if (!matrix.isIdentity()) {
      Bitmap bmRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
      bitmap.recycle();
      return bmRotated;
    }
    return bitmap;
  }

  private Uri saveFile(Bitmap bitmap, String folderName, String fileName, int quality) throws IOException {
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
      if (file.exists())
        file.delete();

      FileOutputStream out = new FileOutputStream(file);
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
      out.flush();
      out.close();

      return Uri.fromFile(file);
    }
    return null;
  }

  /**
   * Figure out what ratio we can load our image into memory at while still being bigger than
   * our desired width and height
   *
   * @param options
   * @param reqWidth
   * @param reqHeight
   * @return
   */
  private static int calculateInSampleSize(
      BitmapFactory.Options options, int reqWidth, int reqHeight) {
    // Raw height and width of image
    final int height = options.outHeight;
    final int width = options.outWidth;
    int inSampleSize = 1;

    if (height > reqHeight || width > reqWidth) {

      final int halfHeight = height / 2;
      final int halfWidth = width / 2;

      // Calculate the largest inSampleSize value that is a power of 2 and keeps both
      // height and width larger than the requested height and width.
      while ((halfHeight / inSampleSize) >= reqHeight
          && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2;
      }
    }

    return inSampleSize;
  }

  /**
   * Maintain the aspect ratio so the resulting image does not look smooshed
   *
   * @param origWidth
   * @param origHeight
   * @return
   */
  private int[] calculateAspectRatio(int origWidth, int origHeight, int reqWidth, int reqHeight) {
    int newWidth = reqWidth;
    int newHeight = reqHeight;

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

  public void copyExif(ExifInterface originalExif, String newPath, int newWidth, int newHeight) throws IOException {

    String[] attributes = new String[]
        {
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_WHITE_BALANCE
        };

    ExifInterface newExif = new ExifInterface(newPath);
    for (int i = 0; i < attributes.length; i++) {
      String value = originalExif.getAttribute(attributes[i]);
      if (value != null) {
        newExif.setAttribute(attributes[i], value);
      }
    }

    newExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(switchWidthAndHeightExifs ? newHeight : newWidth));
    newExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(switchWidthAndHeightExifs ? newWidth : newHeight));
    newExif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
    newExif.saveAttributes();
  }

}
