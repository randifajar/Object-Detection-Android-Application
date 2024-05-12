package com.detection.tomato;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity implements Runnable {

    private static final int NOTIFICATION_ID = 0;
    private static final String NOTIFICATION_ID_STRING = "Notifikasi Hasil Deteksi";
    final Handler handler = new Handler();
    private ImageView mImageView;
    private ResultView mResultView;
    private ProgressBar mProgressBar;
    private Button mResultsButton;
    FirebaseStorage storage;
    private Bitmap mBitmap = null;
    private Module mModule = null;
    private float mImgScaleX, mImgScaleY, mIvScaleX, mIvScaleY, mStartX, mStartY;
        public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        storage = FirebaseStorage.getInstance();

        final Button buttonSelect = findViewById(R.id.resultsButton);
        buttonSelect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, RetrieveDatabase.class);
                startActivity(intent);
            }
        });

        mImageView = findViewById(R.id.imageView);
        mImageView.setImageBitmap(mBitmap);
        mResultView = findViewById(R.id.resultView);
        mResultView.setVisibility(View.INVISIBLE);

        refresh();

        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Log.d("Main Activity", "Refresh Suskses");
                refresh();
                handler.postDelayed(this, 10000);
            }
        };
        handler.postDelayed(runnable, 10000);

        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);

        try {
            mModule = LiteModuleLoader.load(MainActivity.assetFilePath(getApplicationContext(), "best_optimize.torchscript.ptl"));
            BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("classes.txt")));
            String line;
            List<String> classes = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                classes.add(line);
            }
            PrePostProcessor.mClasses = new String[classes.size()];
            classes.toArray(PrePostProcessor.mClasses);
        } catch (IOException e) {
            Log.e("Object Detection", "Model tidak ditemukan!", e);
            finish();
        }
    }

    public void detect(){
        mProgressBar.setVisibility(ProgressBar.VISIBLE);

        mImgScaleX = (float)mBitmap.getWidth() / PrePostProcessor.mInputWidth;
        mImgScaleY = (float)mBitmap.getHeight() / PrePostProcessor.mInputHeight;

        mIvScaleX = (mBitmap.getWidth() > mBitmap.getHeight() ? (float)mImageView.getWidth() / mBitmap.getWidth() : (float)mImageView.getHeight() / mBitmap.getHeight());
        mIvScaleY  = (mBitmap.getHeight() > mBitmap.getWidth() ? (float)mImageView.getHeight() / mBitmap.getHeight() : (float)mImageView.getWidth() / mBitmap.getWidth());

        mStartX = (mImageView.getWidth() - mIvScaleX * mBitmap.getWidth())/2;
        mStartY = (mImageView.getHeight() -  mIvScaleY * mBitmap.getHeight())/2;

        Thread thread = new Thread(MainActivity.this);
        thread.start();
    }

    @Override
    public void run() {
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(mBitmap, PrePostProcessor.mInputWidth, PrePostProcessor.mInputHeight, true);
        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, PrePostProcessor.NO_MEAN_RGB, PrePostProcessor.NO_STD_RGB);
        IValue[] outputTuple = mModule.forward(IValue.from(inputTensor)).toTuple();
        final Tensor outputTensor = outputTuple[0].toTensor();
        final float[] outputs = outputTensor.getDataAsFloatArray();
        final ArrayList<Result> results =  PrePostProcessor.outputsToNMSPredictions(outputs, mImgScaleX, mImgScaleY, mIvScaleX, mIvScaleY, mStartX, mStartY);
        List<String> matang = new ArrayList<>();

        for (Result result : results) {
            if (PrePostProcessor.mClasses[result.classIndex].equals("matang")) {
                matang.add(PrePostProcessor.mClasses[result.classIndex]);
            }
        }

        uploadImageToFirebase(mBitmap, results);

        if (matang.size() > 0) {
            sendNotification(matang);
        }

        runOnUiThread(() -> {
            mProgressBar.setVisibility(ProgressBar.INVISIBLE);
            mResultView.setResults(results);
            mResultView.invalidate();
            mResultView.setVisibility(View.VISIBLE);
        });
    }

    public void sendNotification(List<String> matang) {

        NotificationManager mNotifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_ID_STRING, "Deteksi Tomat Matang", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Notifikasi akan muncul jika tomat terdeteksi matang.");
        mNotifyManager.createNotificationChannel(channel);

        NotificationCompat.Builder notifyBuilder
                = new NotificationCompat.Builder(this, NOTIFICATION_ID_STRING)
                .setContentTitle("Ada Tomat yang Matang!")
                .setContentText("Jumlah tomat yang sudah matang: " + matang.size() + " buah.")
                .setSmallIcon(R.mipmap.ic_launcher);
        Notification myNotification = notifyBuilder.build();
        mNotifyManager.notify(NOTIFICATION_ID, myNotification);
    }

    public void uploadImageToFirebase(Bitmap originalBitmap, ArrayList<Result> results) {
        Date now = new Date();
        StorageReference storageRef = storage.getReference().child("detection_result/"+now+".jpg");
        int w = 1440;
        int h = 1440;
        int spaceLeft = 0;
        int spaceTop = 0;

        Bitmap baseBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        if (originalBitmap.getWidth() > originalBitmap.getHeight()) {
            h = (1440 * originalBitmap.getHeight()) / originalBitmap.getWidth();
            spaceTop = (1440 - h) / 2;
        } else if (originalBitmap.getWidth() < originalBitmap.getHeight()) {
            w = (1440 * originalBitmap.getWidth()) / originalBitmap.getHeight();
            spaceLeft = (1440 - w) / 2;
        }

        Bitmap resultBitmap = Bitmap.createScaledBitmap(originalBitmap.copy(Bitmap.Config.ARGB_8888, true), w, h, false);

        Canvas canvas = new Canvas(baseBitmap);
        canvas.drawBitmap(resultBitmap, spaceLeft, spaceTop, null);

        Paint paintRectangle = new Paint();
        paintRectangle.setColor(Color.RED);
        Paint paintText = new Paint();

        for (Result result : results) {
            paintRectangle.setStrokeWidth(7);
            paintRectangle.setStyle(Paint.Style.STROKE);
            canvas.drawRect(result.rect, paintRectangle);

            Path mPath = new Path();
            RectF mRectF = new RectF(result.rect.left, result.rect.top, result.rect.left + 350,  result.rect.top + 50);
            mPath.addRect(mRectF, Path.Direction.CW);
            paintText.setColor(Color.RED);
            canvas.drawPath(mPath, paintText);

            paintText.setColor(Color.WHITE);
            paintText.setStrokeWidth(0);
            paintText.setStyle(Paint.Style.FILL);
            paintText.setTextSize(32);
            canvas.drawText(String.format("%s %.2f", PrePostProcessor.mClasses[result.classIndex], result.score), result.rect.left + 40, result.rect.top + 35, paintText);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baseBitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        byte[] data = baos.toByteArray();
        storageRef.putBytes(data);
    }

    public void refresh() {
        StorageReference storageRef = storage.getReferenceFromUrl("https://firebasestorage.googleapis.com/v0/b/iot-123180079.appspot.com/o/data%2Fphoto.jpg");
        storageRef.getDownloadUrl().addOnSuccessListener(uri -> Picasso.get().load(uri).into(new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                Matrix matrix = new Matrix();
                mBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                mImageView.setImageBitmap(mBitmap);
                detect();
            }
            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {
            }
            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {
            }
        })).addOnFailureListener(exception -> Toast.makeText(getApplicationContext(), "Gagal Mengambil Data", Toast.LENGTH_SHORT).show());
    }
}
